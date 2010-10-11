// Copyright 2010 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "google/cacheinvalidation/invalidation-client-impl.h"

#include <string>

#include "google/cacheinvalidation/log-macro.h"
#include "google/cacheinvalidation/persistence-utils.h"
#include "google/cacheinvalidation/stl-namespace.h"

namespace invalidation {

using INVALIDATION_STL_NAMESPACE::string;

const char* InvalidationClientImpl::INVALIDATE_ALL_OBJECT_NAME = "ALL";

InvalidationClientImpl::InvalidationClientImpl(
    SystemResources* resources,
    const ClientType& client_type,
    const string& app_name,
    const string& serialized_state,
    const ClientConfig& config,
    InvalidationListener* listener)
  : resources_(resources),
    listener_(listener),
    config_(config),
    network_manager_(ALLOW_THIS_IN_INITIALIZER_LIST(this),
                     resources, config),
    persistence_manager_(resources_),
    awaiting_seqno_writeback_(false),
    random_(resources->current_time().ToInternalValue()) {
  // Initialize the registration and session managers from persisted state if
  // present.
  TiclState persistent_state;
  uint64 initial_seqno;
  string uniquifier;
  string session_token;
  bool persistent = DeserializeState(serialized_state, &persistent_state);
  if (!persistent && !serialized_state.empty()) {
    TLOG(SEVERE_LEVEL, "Got persisted state but failed to deserialize");
  }
  if (persistent) {
    // The Ticl is being restarted with a uniquifier, session token, and
    // sequence number from persistent storage.  In this case, we initialize a
    // session manager with these persisted values, and we start the
    // registration manager with the persisted sequence number.  Before we can
    // send out any registrations, we need to write back a new state blob
    // reserving a new block of sequence numbers.  If that fails, then we need
    // to forget the persisted client id and session and do a fresh start.
    uniquifier = persistent_state.uniquifier();
    session_token = persistent_state.session_token();
    initial_seqno = persistent_state.sequence_number_limit();
  } else {
    // Either we had no persisted state, or we couldn't parse it, so we'll start
    // fresh.  When we get a session, we'll attempt to write out our state.  In
    // the case of a non-persistent client, the write will appear to succeed.
    uniquifier = "";
    session_token = "";
    initial_seqno = RegistrationUpdateManager::kFirstSequenceNumber;
  }
  session_manager_.reset(
      new SessionManager(config, client_type, app_name, resources, uniquifier,
                         session_token));
  registration_manager_.reset(
      new RegistrationUpdateManager(resources, config, initial_seqno,
                                    listener_));
  if (persistent) {
    // If we started from persisted state, then we "have" a session already, and
    // we need to write back a state blob to claim a new block of sequence
    // numbers.
    TLOG(INFO_LEVEL, "Taking session actions for persistent state restart");
    registration_manager_->HandleNewSession();
    resources_->ScheduleOnListenerThread(
        NewPermanentCallback(
            listener_,
            &InvalidationListener::SessionStatusChanged,
            true));
    AllocateNewSequenceNumbers(persistent_state);
  } else {
    // If we're starting fresh, then we can claim an initial block of sequence
    // numbers without writing out state.  When we get a session, we'll attempt
    // to update the state with the session token, etc.
    TLOG(INFO_LEVEL, "Taking actions for fresh start");
    registration_manager_->UpdateMaximumSeqno(config_.seqno_block_size);
  }

  resources->ScheduleImmediately(
      NewPermanentCallback(this, &InvalidationClientImpl::PeriodicTask));
}

void InvalidationClientImpl::AllocateNewSequenceNumbers(
    const TiclState& persistent_state) {
  int64 maximum_op_seqno_inclusive =
      persistent_state.sequence_number_limit() + config_.seqno_block_size;
  TiclState new_state;
  new_state.CopyFrom(persistent_state);
  new_state.set_sequence_number_limit(maximum_op_seqno_inclusive);
  awaiting_seqno_writeback_ = true;
  string serialized;
  SerializeState(new_state, &serialized);
  persistence_manager_.WriteState(
      serialized,
      NewPermanentCallback(
          this,
          &InvalidationClientImpl::HandleSeqnoWritebackResult,
          maximum_op_seqno_inclusive));
}

void InvalidationClientImpl::HandleSeqnoWritebackResult(
    int64 maximum_op_seqno_inclusive, bool success) {
  MutexLock m(&lock_);

  TLOG(INFO_LEVEL, "seqno writeback returned %d", success);
  awaiting_seqno_writeback_ = false;
  if (success) {
    registration_manager_->UpdateMaximumSeqno(maximum_op_seqno_inclusive);
  } else {
    // If we can't reserve a new block of sequence numbers, start over with a
    // new client id.  When we receive the new client id, we'll retry writing
    // the state blob.  If it succeeds, then we'll become a persistent client
    // with that id.  If it fails, then we'll be a non-persistent client with
    // that id.  That's safe, because there can't be any existing operations for
    // that new id.  In the current case, the write must succeed for us to
    // proceed, since otherwise we might end up reusing sequence numbers the
    // next time we restart.
    ForgetClientId();
  }
}

void InvalidationClientImpl::HandleBestEffortWrite(bool result) {
  TLOG(INFO_LEVEL, "Write completed with result: %d", result);
}

class Finally {
 public:
  Finally(Closure* task) : task_(task) {
    CHECK(IsCallbackRepeatable(task));
  }

  ~Finally() {
    task_->Run();
    delete task_;
  }

 private:
  Closure* task_;
};

void InvalidationClientImpl::PeriodicTask() {
  MutexLock m(&lock_);

  // Reschedule the periodic task at the end, however we exit this function.
  TimeDelta smeared_delay = SmearDelay(
      config_.periodic_task_interval, config_.smear_factor, &random_);
  Finally reschedule_periodic_task(
      NewPermanentCallback(
          resources_,
          &SystemResources::ScheduleWithDelay,
          smeared_delay,
          NewPermanentCallback(this, &InvalidationClientImpl::PeriodicTask)));

  persistence_manager_.DoPeriodicCheck();
  if (awaiting_seqno_writeback_) {
    TLOG(INFO_LEVEL, "Skipping periodic check while awaiting local write");
    // Don't send any messages until the initial write-back has finished.
    return;
  }

  // Check if we have run out of sequence numbers.  If so, restart as a new
  // client.
  if (registration_manager_->current_op_seqno() >
      registration_manager_->maximum_op_seqno_inclusive()) {
    TLOG(INFO_LEVEL, "Exhausted seqnos; forgetting client id");
    ForgetClientId();
  }

  // Check for session data to send.
  bool have_session_data = session_manager_->HasDataToSend();

  // Check for registrations to send.
  bool have_registration_data =
      registration_manager_->DoPeriodicRegistrationCheck();

  // Check to see if we need to send a heartbeat or poll.
  bool should_heartbeat_or_poll = network_manager_.HasDataToSend();

  // If there's no session data to send, and we don't have a session, then we
  // can't send anything.
  if (!have_session_data && !session_manager_->HasSession()) {
    TLOG(INFO_LEVEL,
         "Not sending data since no session and session request in-flight");
  } else if (have_session_data || have_registration_data ||
             should_heartbeat_or_poll) {
    network_manager_.OutboundDataReady();
  }
}

void InvalidationClientImpl::Register(const ObjectId& oid) {
  CHECK(!resources_->IsRunningOnInternalThread());
  MutexLock m(&lock_);
  TLOG(INFO_LEVEL, "Received register for %d/%s", oid.source(),
       oid.name().string_value().c_str());
  registration_manager_->Register(oid);
}

void InvalidationClientImpl::Unregister(const ObjectId& oid) {
  CHECK(!resources_->IsRunningOnInternalThread());
  MutexLock m(&lock_);
  TLOG(INFO_LEVEL, "Received unregister for %d/%s", oid.source(),
       oid.name().string_value().c_str());
  registration_manager_->Unregister(oid);
}

void InvalidationClientImpl::PermanentShutdown() {
  CHECK(!resources_->IsRunningOnInternalThread());
  MutexLock m(&lock_);
  TLOG(INFO_LEVEL, "Doing permanent shutdown by application request");
  session_manager_->Shutdown();
}

void InvalidationClientImpl::HandleNewSession() {
  string client_uniquifier = session_manager_->client_uniquifier();

  TLOG(INFO_LEVEL, "Received new session: %s", client_uniquifier.c_str());

  registration_manager_->HandleNewSession();
  network_manager_.RecordImplicitHeartbeat();
  TiclState state;
  string uniquifier = session_manager_->client_uniquifier();
  state.set_uniquifier(uniquifier);
  state.set_session_token(session_manager_->session_token());
  state.set_sequence_number_limit(
      registration_manager_->maximum_op_seqno_inclusive());

  string serialized;
  SerializeState(state, &serialized);
  persistence_manager_.WriteState(
      serialized,
      NewPermanentCallback(
          this,
          &InvalidationClientImpl::HandleBestEffortWrite));

  // Tell the listener we acquired a session and that its registrations were
  // removed.
  resources_->ScheduleOnListenerThread(
      NewPermanentCallback(
          listener_,
          &InvalidationListener::SessionStatusChanged,
          true));
}

void InvalidationClientImpl::HandleLostSession() {
  registration_manager_->HandleLostSession();
  resources_->ScheduleOnListenerThread(
      NewPermanentCallback(
          // Tell the listener we lost our session.
          listener_, &InvalidationListener::SessionStatusChanged, false));
}

void InvalidationClientImpl::HandleObjectControl(
    const ServerToClientMessage& bundle) {
  // Handle registration response.
  registration_manager_->ProcessInboundMessage(bundle);
  // Process invalidations.
  for (int i = 0; i < bundle.invalidation_size(); ++i) {
    ProcessInvalidation(bundle.invalidation(i));
  }
}

void InvalidationClientImpl::HandleInboundMessage(const string& message) {
  CHECK(!resources_->IsRunningOnInternalThread());
  MutexLock m(&lock_);

  if (awaiting_seqno_writeback_) {
    // If the initial write back to allocate sequence numbers hasn't returned,
    // don't process any messages, since they could cause state changes that
    // would require substantial complexity to handle.
    TLOG(INFO_LEVEL, "Dropping inbound message since seqno write in-progress");
    return;
  }

  ServerToClientMessage bundle;
  bundle.ParseFromString(message);

  MessageAction action = session_manager_->ProcessMessage(bundle);

  TLOG(INFO_LEVEL, "Classified inbound message as %d", action);
  switch (action) {
    case IGNORE_MESSAGE:
      TLOG(INFO_LEVEL, "Ignored last received message");
      return;
    case ACQUIRE_SESSION:
      HandleNewSession();
      break;
    case LOSE_CLIENT_ID:
      ForgetClientId();
      break;
    case LOSE_SESSION:
      HandleLostSession();
      break;
    case PROCESS_OBJECT_CONTROL:
      HandleObjectControl(bundle);
      break;
    default:
      // Can't happen.
      TLOG(INFO_LEVEL, "Unknown message action: %d", action);
      return;  // Don't process the new polling/heartbeat intervals.
  }

  // Let the network manager acquire new polling and heartbeat intervals.  All
  // cases that reach here verified that the message was addressed to this
  // client.
  network_manager_.HandleInboundMessage(bundle);
}

/* Handles an invalidation. */
void InvalidationClientImpl::ProcessInvalidation(
    const Invalidation& invalidation) {
  Closure* callback =
      NewPermanentCallback(
          this, &InvalidationClientImpl::ScheduleAcknowledgeInvalidation,
          invalidation);

  const ObjectId& oid = invalidation.object_id();
  if ((oid.source() == ObjectId_Source_INTERNAL) &&
      (oid.name().string_value() == INVALIDATE_ALL_OBJECT_NAME)) {
    resources_->ScheduleOnListenerThread(
        NewPermanentCallback(listener_, &InvalidationListener::InvalidateAll,
                             callback));
  } else {
    resources_->ScheduleOnListenerThread(
        NewPermanentCallback(listener_, &InvalidationListener::Invalidate,
                             invalidation, callback));
  }
}

void InvalidationClientImpl::AcknowledgeInvalidation(
    const Invalidation& invalidation) {

  MutexLock m(&lock_);
  pending_invalidation_acks_.push_back(invalidation);
  network_manager_.OutboundDataReady();
}

void InvalidationClientImpl::ScheduleAcknowledgeInvalidation(
    const Invalidation& invalidation) {

  resources_->ScheduleImmediately(
      NewPermanentCallback(this,
                           &InvalidationClientImpl::AcknowledgeInvalidation,
                           invalidation));
}

void InvalidationClientImpl::RegisterOutboundListener(
    NetworkCallback* outbound_message_ready) {
  CHECK(!resources_->IsRunningOnInternalThread());
  MutexLock m(&lock_);
  network_manager_.RegisterOutboundListener(outbound_message_ready);
}

void InvalidationClientImpl::TakeOutboundMessage(string* serialized) {
  CHECK(!resources_->IsRunningOnInternalThread());
  MutexLock m(&lock_);

  ClientToServerMessage message;

  // If PermanentShutdown() has been called, the session manager will return a
  // message of TYPE_SHUTDOWN.
  session_manager_->AddSessionAction(&message);

  // If the session manager didn't set a message type, then we can let the
  // registration manager add fields.
  if (!message.has_message_type()) {
    registration_manager_->AddOutboundData(&message);
  } else {
    TLOG(INFO_LEVEL, "message had type %d, not giving to reg manager",
         message.message_type());
  }

  // If the registration manager is sending an OBJECT_CONTROL message, we can
  // let the network manager try to attach a heartbeat to it if needed, and we
  // can send invalidation acks.
  if (message.message_type() ==
      ClientToServerMessage_MessageType_TYPE_OBJECT_CONTROL) {
    network_manager_.AddHeartbeat(&message);

    // Add up to maxRegistrationsPerMessage registrations.
    int invalidation_acks_sent = 0;
    int registration_count = message.register_operation_size();

    // Add any outbound invalidations, up to max_ops_per_message. We ack the
    // newest invalidations first (since we pop from the array), which is good,
    // because an invalidation for a newer version of an object subsumes an
    // older invalidation.
    while (!pending_invalidation_acks_.empty() &&
           (registration_count + invalidation_acks_sent <
            config_.max_ops_per_message)) {
      ++invalidation_acks_sent;
      Invalidation* inv = message.add_acked_invalidation();
      inv->CopyFrom(pending_invalidation_acks_.back());
      // If the invalidation contains a component stamp log, add a client stamp.
      if (inv->has_component_stamp_log()) {
        ComponentStamp* stamp = inv->mutable_component_stamp_log()->add_stamp();
        stamp->set_component("C");  // "C" -> Client.
        // Internal time value is in microseconds; stamp log should be in
        // millis.
        stamp->set_time(resources_->current_time().ToInternalValue() /
                        Time::kMicrosecondsPerMillisecond);
      }
      pending_invalidation_acks_.pop_back();
    }
  }
  // Regardless, we'll let the network manager add a message id and signal data
  // to send.
  network_manager_.FinalizeOutboundMessage(&message);
  CHECK(message.has_message_type());
  CHECK(message.has_client_type());
  message.SerializeToString(serialized);
}

TimeDelta InvalidationClientImpl::SmearDelay(
    TimeDelta base_delay, double smear_factor, Random* random) {
  CHECK(smear_factor >= 0.0);
  CHECK(smear_factor <= 1.0);
  // 2*r - 1 gives us a number in [-1, 1]
  double normalized_rand = random->RandDouble();
  double applied_smear = smear_factor * (2.0 * normalized_rand - 1.0);
  return TimeDelta::FromMicroseconds(
      static_cast<int64>(
          base_delay.InMicroseconds() * (applied_smear + 1.0)));
}

}  // namespace invalidation
