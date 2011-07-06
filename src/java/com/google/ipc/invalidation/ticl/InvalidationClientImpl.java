/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ipc.invalidation.ticl;

import static com.google.ipc.invalidation.external.client.SystemResources.Scheduler.NO_DELAY;

import com.google.common.base.Preconditions;
import com.google.ipc.invalidation.common.CommonInvalidationConstants2;
import com.google.ipc.invalidation.common.CommonProtoStrings2;
import com.google.ipc.invalidation.common.CommonProtos2;
import com.google.ipc.invalidation.common.DigestFunction;
import com.google.ipc.invalidation.common.ObjectIdDigestUtils;
import com.google.ipc.invalidation.common.TiclMessageValidator2;
import com.google.ipc.invalidation.external.client.InvalidationListener;
import com.google.ipc.invalidation.external.client.SystemResources;
import com.google.ipc.invalidation.external.client.SystemResources.Logger;
import com.google.ipc.invalidation.external.client.SystemResources.Scheduler;
import com.google.ipc.invalidation.external.client.types.AckHandle;
import com.google.ipc.invalidation.external.client.types.Callback;
import com.google.ipc.invalidation.external.client.types.ErrorInfo;
import com.google.ipc.invalidation.external.client.types.Invalidation;
import com.google.ipc.invalidation.external.client.types.ObjectId;
import com.google.ipc.invalidation.external.client.types.SimplePair;
import com.google.ipc.invalidation.external.client.types.Status;
import com.google.ipc.invalidation.ticl.ProtocolHandler.ProtocolListener;
import com.google.ipc.invalidation.ticl.ProtocolHandler.ServerMessageHeader;
import com.google.ipc.invalidation.ticl.Statistics.ClientErrorType;
import com.google.ipc.invalidation.ticl.Statistics.IncomingOperationType;
import com.google.ipc.invalidation.util.Bytes;
import com.google.ipc.invalidation.util.ExponentialBackoffDelayGenerator;
import com.google.ipc.invalidation.util.InternalBase;
import com.google.ipc.invalidation.util.Smearer;
import com.google.ipc.invalidation.util.TextBuilder;
import com.google.ipc.invalidation.util.TypedUtil;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protos.ipc.invalidation.Client.AckHandleP;
import com.google.protos.ipc.invalidation.Client.PersistentTiclState;
import com.google.protos.ipc.invalidation.ClientProtocol.ApplicationClientIdP;
import com.google.protos.ipc.invalidation.ClientProtocol.ErrorMessage;
import com.google.protos.ipc.invalidation.ClientProtocol.InfoRequestMessage.InfoType;
import com.google.protos.ipc.invalidation.ClientProtocol.InvalidationP;
import com.google.protos.ipc.invalidation.ClientProtocol.ObjectIdP;
import com.google.protos.ipc.invalidation.ClientProtocol.RegistrationP;
import com.google.protos.ipc.invalidation.ClientProtocol.RegistrationStatus;
import com.google.protos.ipc.invalidation.ClientProtocol.RegistrationSubtree;
import com.google.protos.ipc.invalidation.ClientProtocol.RegistrationSummary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;


/**
 * Implementation of the  Invalidation Client Library (Ticl).
 *
 */
public class InvalidationClientImpl extends InternalBase
    implements TestableInvalidationClient, ProtocolListener {

  /** Configuration for client. */
  public static class Config extends InternalBase {

    /** The delay after which a network message sent to the server is considered timed out. */
    public int networkTimeoutDelayMs = 60 * 1000;

    /** Retry delay for a persistent write if it fails. */
    public int writeRetryDelayMs = 10 * 1000;

    /** Delay for sending heartbeats to the server. */
    public int heartbeatIntervalMs = 20 * 60 * 1000;

    /** Delay after which performance counters are sent to the server. */
    public int perfCounterDelayMs = 6 * 60 * 60 * 1000;  // 6 hours.

    /** The maximum exponential backoff factor used for network and persistence timeouts. */
    public int maxExponentialBackoffFactor = 500;

    /** Configuration for the protocol client to control batching etc. */
    public ProtocolHandler.Config protocolHandlerConfig = new ProtocolHandler.Config();

    /**
     * Modifies {@code configParams} to contain the list of configuration parameter names and their
     * values.
     */
    public void getConfigParams(List<SimplePair<String, Integer>> configParams) {
      configParams.add(SimplePair.of("networkTimeoutDelayMs", networkTimeoutDelayMs));
      configParams.add(SimplePair.of("writeRetryDelayMs", writeRetryDelayMs));
      configParams.add(SimplePair.of("heartbeatIntervalMs", heartbeatIntervalMs));
      configParams.add(SimplePair.of("perfCounterDelayMs", perfCounterDelayMs));
      configParams.add(SimplePair.of("maxExponentialBackoffFactor", maxExponentialBackoffFactor));
      protocolHandlerConfig.getConfigParams(configParams);
    }

    @Override
    public void toCompactString(TextBuilder builder) {
      builder.appendFormat("Network delay: %s, write retry delay: %s, heartbeat: %s ",
          networkTimeoutDelayMs, writeRetryDelayMs, heartbeatIntervalMs);
      protocolHandlerConfig.toCompactString(builder);
    }

    /** Returns a configuration object with parameters set for unit tests. */
    public static InvalidationClientImpl.Config createConfigForTest() {
      InvalidationClientImpl.Config config = new InvalidationClientImpl.Config();
      config.networkTimeoutDelayMs = 2 * 1000;
      config.protocolHandlerConfig.batchingDelayMs = 200;
      config.heartbeatIntervalMs = 5 * 1000;
      config.writeRetryDelayMs = 500;
      return config;
    }
  }

  /** The single key used to write all the Ticl state. */
  private static final byte[] CLIENT_TOKEN_KEY = "ClientToken".getBytes();

  /** Resources for the Ticl. */
  private final SystemResources resources;

  /**
   * Reference into the resources object for cleaner code. All Ticl code must be scheduled
   * on this scheduler.
   */
  private final Scheduler internalScheduler;

  /** Logger reference into the resources object for cleaner code. */
  private final Logger logger;

  /** Application callback interface. */
  private final CheckingInvalidationListener listener;

  /** Configuration for this instance. */
  private final Config config;

  /** The client type code as assigned by the notification system's backend. */
  private final int clientType;

  /** Application identifier for this client. */
  private final ApplicationClientIdP applicationClientId;

  /** Object maintaining the registration state for this client. */
  private final RegistrationManager registrationManager;

  /** Object handling low-level wire format interactions. */
  private final ProtocolHandler protocolHandler;

  /** Object to schedule future events. */
  private final OperationScheduler operationScheduler;

  /** Used to validate messages */
  private final TiclMessageValidator2 msgValidator;

  /** The function for computing the registration and persistence state digests. */
  private final DigestFunction digestFn = new ObjectIdDigestUtils.Sha1DigestFunction();

  /** The state of the Ticl whether it has started or not. */
  private final RunState ticlState = new RunState();

  /** Statistics objects to track number of sent messages, etc. */
  private final Statistics statistics = new Statistics();

  /** Last time performance counters were sent to the server. */
  private long lastPerformanceSendTimeMs = 0;

  /** Exponential backoff generator for acquire-token timeouts. */
  private final ExponentialBackoffDelayGenerator tokenExponentialBackoff;

  /** Exponential backoff generator for persistence timeouts. */
  private final ExponentialBackoffDelayGenerator persistenceExponentialBackoff;

  /** A smearer to make sure that delays are randomized a little bit. */
  private final Smearer smearer;

  /** Current client token known from the server. */
  private ByteString clientToken = null;

  // After the client starts, exactly one of nonce and clientToken is non-null.

  /** If not {@code null}, nonce for pending identifier request. */
  private ByteString nonce = null;

  /** A task for periodic heartbeats. */
  private final Runnable heartbeatTask = new Runnable() {
    @Override
    public void run() {
      // Send info message
      logger.info("Sending heartbeat to server: %s", this);
      sendInfoMessageToServer(false);
      operationScheduler.schedule(this);
    }
  };

  /** A task to periodically check network timeouts. */
  private final Runnable timeoutTask = new Runnable() {
    @Override
    public void run() {
      checkNetworkTimeouts();
    }
  };

  /**
   * Constructs a client.
   *
   * @param resources resources to use during execution
   * @param clientType client type code
   * @param clientName application identifier for the client
   * @param config configuration for the client
   * @param applicationName name of the application using the library (for debugging/monitoring)
   * @param listener application callback
   */
  public InvalidationClientImpl(final SystemResources resources, int clientType,
      final byte[] clientName, Config config, String applicationName,
      InvalidationListener listener) {
    this.resources = resources;
    this.logger = resources.getLogger();
    this.internalScheduler = resources.getInternalScheduler();
    this.config = config;
    this.registrationManager = new RegistrationManager(logger, statistics, digestFn);
    Random random = new Random();
    this.tokenExponentialBackoff = new ExponentialBackoffDelayGenerator(random,
        config.maxExponentialBackoffFactor * config.networkTimeoutDelayMs,
        config.networkTimeoutDelayMs);
    this.persistenceExponentialBackoff = new ExponentialBackoffDelayGenerator(random,
        config.maxExponentialBackoffFactor * config.writeRetryDelayMs, config.writeRetryDelayMs);
    this.smearer = new Smearer(random);
    this.clientType = clientType;
    this.applicationClientId =
        CommonProtos2.newApplicationClientIdP(ByteString.copyFrom(clientName));
    this.listener = new CheckingInvalidationListener(listener, statistics, internalScheduler,
        resources.getListenerScheduler(), logger);
    this.operationScheduler = new OperationScheduler(logger, internalScheduler);
    this.msgValidator = new TiclMessageValidator2(resources.getLogger());

    operationScheduler.setOperation(config.networkTimeoutDelayMs, timeoutTask);
    operationScheduler.setOperation(config.heartbeatIntervalMs, heartbeatTask);
    this.protocolHandler = new ProtocolHandler(config.protocolHandlerConfig, resources,
        statistics, applicationName, this, msgValidator);
    logger.info("Created client: %s", this);
  }

  // Methods for TestableInvalidationClient.

  @Override
  
  public byte[] getApplicationClientIdForTest() {
    return applicationClientId.toByteArray();
  }

  @Override
  
  public InvalidationListener getInvalidationListenerForTest() {
    return this.listener.getDelegate();
  }

  @Override
  
  public SystemResources getResourcesForTest() {
    return this.resources;
  }

  @Override
  
  public Statistics getStatisticsForTest() {
    Preconditions.checkState(internalScheduler.isRunningOnThread());
    return this.statistics;
  }

  @Override
  
  public DigestFunction getDigestFunctionForTest() {
    return this.digestFn;
  }

  @Override
  
  public long getNextMessageSendTimeMsForTest() {
    Preconditions.checkState(internalScheduler.isRunningOnThread());
    return protocolHandler.getNextMessageSendTimeMsForTest();
  }

  @Override
  
  public SimplePair<RegistrationSummary, ? extends Collection<ObjectIdP>>
      getRegistrationManagerStateCopyForTest() {
    Preconditions.checkState(internalScheduler.isRunningOnThread());
    return registrationManager.getRegistrationManagerStateCopyForTest(
        new ObjectIdDigestUtils.Sha1DigestFunction());
  }

  @Override
  
  public void changeNetworkTimeoutDelayForTest(int delayMs) {
    operationScheduler.changeDelayForTest(timeoutTask, delayMs);
  }

  @Override
  
  public void changeHeartbeatDelayForTest(int delayMs) {
    operationScheduler.changeDelayForTest(heartbeatTask, delayMs);
  }

  @Override
  
  public void setDigestStoreForTest(DigestStore<ObjectIdP> digestStore) {
    Preconditions.checkState(!resources.isStarted());
    registrationManager.setDigestStoreForTest(digestStore);
  }

  @Override
  
  public ByteString getClientTokenForTest() {
    return getClientToken();
  }

  @Override
  
  public byte[] getClientTokenKeyForTest() {
    return CLIENT_TOKEN_KEY;
  }

  // End of methods for TestableInvalidationClient

  @Override
  public void start() {
    resources.start();

    // Initialize the nonce so that we can maintain the invariant that exactly one of
    // "nonce" and "clientToken" is non-null.
    setNonce(ByteString.copyFromUtf8(Long.toString(internalScheduler.getCurrentTimeMs())));

    logger.info("Starting with Java config: %s", config);
    // Read the state blob and then schedule startInternal once the value is there.
    scheduleStartAfterReadingStateBlob();
  }

  /**
   * Implementation of {@link #start} on the internal thread with the persistent
   * {@code serializedState} if any. Starts the TICL protocol and makes the TICL ready to received
   * registration, invalidations, etc
   */
  private void startInternal(byte[] serializedState) {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");

    // Initialize the session manager using the persisted client token.
    PersistentTiclState persistentState =
        (serializedState == null) ? null : PersistenceUtils.deserializeState(logger,
            serializedState, digestFn);

    if ((serializedState != null) && (persistentState == null)) {
      // In this case, we'll proceed as if we had no persistent state -- i.e., obtain a new client
      // id from the server.
      statistics.recordError(ClientErrorType.PERSISTENT_DESERIALIZATION_FAILURE);
      logger.severe("Failed deserializing persistent state: %s",
          CommonProtoStrings2.toLazyCompactString(serializedState));
    }
    if (persistentState != null) {
      // If we have persistent state, use the previously-stored token and send a heartbeat to
      // let the server know that we've restarted, since we may have been marked offline.
      logger.info("Restarting from persistent state: %s",
          CommonProtoStrings2.toLazyCompactString(persistentState.getClientToken()));
      setNonce(null);
      setClientToken(persistentState.getClientToken());
      sendInfoMessageToServer(false);
    } else {
      // If we had no persistent state or couldn't deserialize the state that we had, start fresh.
      // Request a new client identifier.
      logger.info("Starting with no previous state");
      acquireToken("Startup");
    }

    // listener.ready() is called when ticl has acquired a new token.
  }

  @Override
  public void stop() {
    internalScheduler.schedule(NO_DELAY, new Runnable() {
      @Override
      public void run() {
        logger.warning("Ticl being stopped: %s", InvalidationClientImpl.this);
        if (ticlState.isStarted()) {
          ticlState.stop();
        }
        if (resources.isStarted()) {
          resources.stop();
        }
      }
    });
  }

  @Override
  public void register(ObjectId objectId) {
    List<ObjectId> objectIds = new ArrayList<ObjectId>();
    objectIds.add(objectId);
    performRegisterOperations(objectIds, RegistrationP.OpType.REGISTER);
  }

  @Override
  public void unregister(ObjectId objectId) {
    List<ObjectId> objectIds = new ArrayList<ObjectId>();
    objectIds.add(objectId);
    performRegisterOperations(objectIds, RegistrationP.OpType.UNREGISTER);
  }

  @Override
  public void register(Collection<ObjectId> objectIds) {
    performRegisterOperations(objectIds, RegistrationP.OpType.REGISTER);
  }

  @Override
  public void unregister(Collection<ObjectId> objectIds) {
    performRegisterOperations(objectIds, RegistrationP.OpType.UNREGISTER);
  }

  /**
   * Implementation of (un)registration.
   *
   * @param objectIds object ids on which to operate
   * @param regOpType whether to register or unregister
   */
  private void performRegisterOperations(final Collection<ObjectId> objectIds,
      final RegistrationP.OpType regOpType) {
    Preconditions.checkState(!objectIds.isEmpty(), "Must specify some object id");
    Preconditions.checkNotNull(regOpType, "Must specify (un)registration");

    Preconditions.checkState(ticlState.isStarted() || ticlState.isStopped(),
      "Cannot call %s for object %s when the Ticl has not been started. If start has been " +
      "called, caller must wait for InvalidationListener.ready", regOpType, objectIds);
    if (ticlState.isStopped()) {
      // The Ticl has been stopped. This might be some old registration op coming in. Just ignore
      // instead of crashing.
      logger.warning("Ticl stopped: register (%s) of %s ignored.", regOpType, objectIds);
      return;
    }

    internalScheduler.schedule(NO_DELAY, new Runnable() {
      @Override
      public void run() {
        List<ObjectIdP> objectIdProtos = new ArrayList<ObjectIdP>(objectIds.size());
        for (ObjectId objectId : objectIds) {
          Preconditions.checkNotNull(objectId, "Must specify object id");
          ObjectIdP objectIdProto = ProtoConverter.convertToObjectIdProto(objectId);
          IncomingOperationType opType = (regOpType == RegistrationP.OpType.REGISTER) ?
              IncomingOperationType.REGISTRATION : IncomingOperationType.UNREGISTRATION;
          statistics.recordIncomingOperation(opType);
          logger.info("Register %s, %s", objectIdProto, regOpType);
          objectIdProtos.add(objectIdProto);
        }

        // Update the registration manager state, then have the protocol client send a message.
        registrationManager.performOperations(objectIdProtos, regOpType);
        protocolHandler.sendRegistrations(objectIdProtos, regOpType);
        operationScheduler.schedule(timeoutTask);
      }
    });
  }

  @Override
  public void acknowledge(final AckHandle acknowledgeHandle) {
    Preconditions.checkNotNull(acknowledgeHandle);
    internalScheduler.schedule(NO_DELAY, new Runnable() {
      @Override
      public void run() {
        // Validate the ack handle.

        // 1. Parse the ack handle first.
        AckHandleP ackHandle;
        try {
          ackHandle = AckHandleP.parseFrom(acknowledgeHandle.getHandleData());
        } catch (InvalidProtocolBufferException exception) {
          logger.warning("Bad ack handle : %s",
            CommonProtoStrings2.toLazyCompactString(acknowledgeHandle.getHandleData()));
          statistics.recordError(ClientErrorType.ACKNOWLEDGE_HANDLE_FAILURE);
          return;
        }

        // 2. Validate ack handle - it should have a valid invalidation.
        if (!ackHandle.hasInvalidation() ||
            !msgValidator.isValid(ackHandle.getInvalidation())) {
          logger.warning("Incorrect ack handle: %s", ackHandle);
          statistics.recordError(ClientErrorType.ACKNOWLEDGE_HANDLE_FAILURE);
          return;
        }

        // Currently, only invalidations have non-trivial ack handle.
        InvalidationP invalidation = ackHandle.getInvalidation();
        statistics.recordIncomingOperation(IncomingOperationType.ACKNOWLEDGE);
        protocolHandler.sendInvalidationAck(invalidation);
      }
    });
  }

  //
  // Protocol listener methods
  //

  @Override
  public ByteString getClientToken() {
    Preconditions.checkState((clientToken == null) || (nonce == null));
    return clientToken;
  }

  @Override
  public void handleTokenChanged(final ServerMessageHeader header,
      final ByteString newToken) {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");

    // If the client token was valid, we have already checked in protocol handler.
    // Otherwise, we need to check for the nonce, i.e., if we have a nonce, the message must
    // carry the same nonce.
    if (nonce != null) {
      if (TypedUtil.<ByteString>equals(header.token, nonce)) {
        logger.info("Accepting server message with matching nonce: %s",
          CommonProtoStrings2.toLazyCompactString(nonce));
        setNonce(null);
      } else {
        statistics.recordError(ClientErrorType.NONCE_MISMATCH);
        logger.info("Rejecting server message with mismatched nonce: %s, %s",
            CommonProtoStrings2.toLazyCompactString(nonce),
            CommonProtoStrings2.toLazyCompactString(header.token));
        return;
      }
    }

    // The message is for us. Process it.
    processServerHeader(header);

    if (newToken == null) {
      logger.info("Destroying existing token: %s",
        CommonProtoStrings2.toLazyCompactString(clientToken));
      acquireToken("Destroy");
    } else {
      // We just received a new token. Start the regular heartbeats now.
      operationScheduler.schedule(heartbeatTask);
      setNonce(null);
      setClientToken(newToken);
      writeStateBlob();
      logger.info("New token assigned at client: %s, Old = %s",
        CommonProtoStrings2.toLazyCompactString(newToken),
        CommonProtoStrings2.toLazyCompactString(clientToken));
    }
  }

  @Override
  public void handleInvalidations(final ServerMessageHeader header,
      final Collection<InvalidationP> invalidations) {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");
    processServerHeader(header);

    for (InvalidationP invalidation : invalidations) {
      AckHandle ackHandle = AckHandle.newInstance(
          CommonProtos2.newAckHandleP(invalidation).toByteArray());
      if (TypedUtil.<ObjectIdP>equals(invalidation.getObjectId(),
          CommonInvalidationConstants2.ALL_OBJECT_ID)) {
        logger.info("Issuing invalidate all");
        listener.invalidateAll(InvalidationClientImpl.this, ackHandle);
      } else {
        // Regular object. Could be unknown version or not.
        Invalidation inv = ProtoConverter.convertFromInvalidationProto(invalidation);
        logger.info("Issuing invalidate (known-version = %s): %s", invalidation.getIsKnownVersion(),
            inv);
        if (invalidation.getIsKnownVersion()) {
          listener.invalidate(InvalidationClientImpl.this, inv, ackHandle);
        } else {
          // Unknown version
          listener.invalidateUnknownVersion(InvalidationClientImpl.this, inv.getObjectId(),
              ackHandle);
        }
      }
    }
  }

  @Override
  public void handleRegistrationStatus(final ServerMessageHeader header,
      final List<RegistrationStatus> regStatusList) {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");
    processServerHeader(header);

    List<Boolean> localProcessingStatuses =
        registrationManager.handleRegistrationStatus(regStatusList);
    Preconditions.checkState(localProcessingStatuses.size() == regStatusList.size(),
        "Not all registration statuses were processed");

    // Inform app about the success or failure of each registration based
    // on what the registration manager has indicated.
    for (int i = 0; i < regStatusList.size(); ++i) {
      RegistrationStatus regStatus = regStatusList.get(i);
      boolean wasSuccess = localProcessingStatuses.get(i);
      logger.fine("Process reg status: %s", regStatus);

      ObjectId objectId = ProtoConverter.convertFromObjectIdProto(
        regStatus.getRegistration().getObjectId());
      if (wasSuccess) {
        InvalidationListener.RegistrationState regState = convertOpTypeToRegState(regStatus);
        listener.informRegistrationStatus(InvalidationClientImpl.this, objectId, regState);
      } else {
        boolean isPermanent = CommonProtos2.isPermanentFailure(regStatus.getStatus());
        listener.informRegistrationFailure(InvalidationClientImpl.this, objectId, !isPermanent,
            regStatus.getStatus().getDescription());
      }
    }
  }

  @Override
  public void handleRegistrationSyncRequest(final ServerMessageHeader header) {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");
    // Send all the registrations in the reg sync message.
    processServerHeader(header);

    // Generate a single subtree for all the registrations.
    RegistrationSubtree subtree =
        registrationManager.getRegistrations(Bytes.EMPTY_BYTES.getByteArray(), 0);
    protocolHandler.sendRegistrationSyncSubtree(subtree);
  }

  @Override
  public void handleInfoMessage(ServerMessageHeader header, Collection<InfoType> infoTypes) {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");
    processServerHeader(header);
    boolean mustSendPerformanceCounters = false;
    for (InfoType infoType : infoTypes) {
      mustSendPerformanceCounters = (infoType == InfoType.GET_PERFORMANCE_COUNTERS);
      if (mustSendPerformanceCounters) {
        break;
      }
    }
    sendInfoMessageToServer(mustSendPerformanceCounters);
  }

  @Override
  public void handleErrorMessage(ServerMessageHeader header, ErrorMessage.Code code,
      String description) {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");
    processServerHeader(header);

    // If it is an auth failure, we shut down the ticl.
    logger.severe("Received error message: %s, %s, %s", header, code, description);

    // Translate the code to error reason.
    int reason;
    switch (code) {
      case AUTH_FAILURE:
        reason = ErrorInfo.ErrorReason.AUTH_FAILURE;
        break;
      case UNKNOWN_FAILURE:
        reason = ErrorInfo.ErrorReason.UNKNOWN_FAILURE;
        break;
      default:
        reason = ErrorInfo.ErrorReason.UNKNOWN_FAILURE;
        break;
    }

    // Issue an informError to the application.
    ErrorInfo errorInfo = ErrorInfo.newInstance(reason, false, description, null);
    listener.informError(this, errorInfo);

    // If this is an auth failure, remove registrations and stop the Ticl. Otherwise do nothing.
    if (code != ErrorMessage.Code.AUTH_FAILURE) {
      return;
    }

    // If there are any registrations, remove them and issue registration failure.
    Collection<ObjectIdP> desiredRegistrations = registrationManager.removeRegisteredObjects();
    logger.warning("Issuing failure for %s objects", desiredRegistrations.size());
    for (ObjectIdP objectId : desiredRegistrations) {
      listener.informRegistrationFailure(this,
        ProtoConverter.convertFromObjectIdProto(objectId), false, "Auth error: " + description);
    }
    // Schedule the stop on the listener work queue so that it happens after the inform
    // registration failure calls above
    resources.getListenerScheduler().schedule(NO_DELAY, new Runnable() {
      @Override
      public void run() {
        stop();
      }
    });
  }

  @Override
  public RegistrationSummary getRegistrationSummary() {
    return registrationManager.getRegistrationSummary();
  }

  //
  // Private methods and toString.
  //

  /**
   * Requests a new client identifier from the server.
   * <p>
   * REQUIRES: no token currently be held.
   *
   * @param debugString information to identify the caller
   */
  private void acquireToken(final String debugString) {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");
    setClientToken(null);

    // Schedule the token acquisition while respecting exponential backoff.
    internalScheduler.schedule(tokenExponentialBackoff.getNextDelay(), new Runnable() {
      @Override
      public void run() {
        // If token is still not assigned (as expected), sends a request. Otherwise, ignore.
        if (clientToken == null) {
          // Allocate a nonce and send a message requesting a new token.
          setNonce(ByteString.copyFromUtf8(Long.toString(internalScheduler.getCurrentTimeMs())));
          protocolHandler.sendInitializeMessage(clientType, applicationClientId, nonce,
              debugString);

          // Schedule a timeout to retry if we don't receive a response.
          operationScheduler.schedule(timeoutTask);
        }
      }
    });
  }

  /** Function called to check for timed-out network messages. */
  private void checkNetworkTimeouts() {
    /*
     * Timeouts can happen for two reasons:
     * 1) Request to obtain an token does not receive a reply.
     * 2) Registration state is not in sync with the server.
     *
     * We simply check for both conditions and taken corrective action when needed.
     */
    // If we have no token, send a message for one.
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");
    if (clientToken == null) {
      logger.info("Request for token timed out");
      acquireToken("Network timeout");
      return;
    }

    // Simply send an info message to ensure syncing happens.
    if (!registrationManager.isStateInSyncWithServer()) {
      logger.info("Registration state not in sync with server: %s", registrationManager);
      sendInfoMessageToServer(false);
      operationScheduler.schedule(timeoutTask);
    }
  }

  /**
   * Processes the header on a server message by updating the latest known server time and informing
   * the registration manager of a new summary.
   * <p>
   * REQUIRES: {@code nonce} be {@code null}.
   */
  private void processServerHeader(ServerMessageHeader header) {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");
    Preconditions.checkState(nonce == null,
        "Cannot process server header with non-null nonce (have %s): %s", nonce, header);
    if (header.registrationSummary != null) {
      registrationManager.informServerRegistrationSummary(header.registrationSummary);
    }
  }

  /**
   * Sends an info message to the server. If {@code mustSendPerformanceCounters} is true,
   * the performance counters are sent regardless of when they were sent earlier.
   */
  private void sendInfoMessageToServer(boolean mustSendPerformanceCounters) {
    logger.info("Sending info message to server");
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");

    long nextPerformanceSendTimeMs = lastPerformanceSendTimeMs +
        smearer.getSmearedDelay(config.perfCounterDelayMs);
    List<SimplePair<String, Integer>> performanceCounters =
        new ArrayList<SimplePair<String, Integer>>();
    List<SimplePair<String, Integer>> configParams =
        new ArrayList<SimplePair<String, Integer>>();
    if (mustSendPerformanceCounters ||
        (nextPerformanceSendTimeMs < internalScheduler.getCurrentTimeMs())) {
      statistics.getNonZeroStatistics(performanceCounters);
      config.getConfigParams(configParams);
      lastPerformanceSendTimeMs = internalScheduler.getCurrentTimeMs();
    }
    protocolHandler.sendInfoMessage(performanceCounters, configParams);
  }

  /** Writes the Ticl state to persistent storage. */
  private void writeStateBlob() {
    Preconditions.checkState(internalScheduler.isRunningOnThread(), "Not on internal thread");
    Preconditions.checkNotNull(clientToken);
    PersistentTiclState state = CommonProtos2.newPersistentTiclState(clientToken);
    byte[] serializedState = PersistenceUtils.serializeState(state, digestFn);
    resources.getStorage().writeKey(CLIENT_TOKEN_KEY, serializedState,
      new Callback<Status>() {
      @Override
      public void accept(Status status) {
        logger.info("Write state completed: %s", status);
        if (!status.isSuccess()) {
          // Retry with exponential backoff.
          statistics.recordError(ClientErrorType.PERSISTENT_WRITE_FAILURE);
          internalScheduler.schedule(
            persistenceExponentialBackoff.getNextDelay(), new Runnable() {
            @Override
            public void run() {
              writeStateBlob();
            }
          });
        } else {
          // Write succeeded - reset the backoff delay.
          persistenceExponentialBackoff.reset(config.writeRetryDelayMs);
        }
      }
    });
  }

  /** Reads the Ticl state from persistent storage (if any) and calls {@code startInternal}. */
  private void scheduleStartAfterReadingStateBlob() {
    resources.getStorage().readKey(CLIENT_TOKEN_KEY, new Callback<SimplePair<Status, byte[]>>() {
      @Override
      public void accept(SimplePair<Status, byte[]> readResult) {
        final byte[] serializedState = readResult.getFirst().isSuccess() ?
            readResult.getSecond() : null;
        if (!readResult.getFirst().isSuccess()) {
          statistics.recordError(ClientErrorType.PERSISTENT_READ_FAILURE);
          logger.warning("Could not read state blob: %s",
              readResult.getFirst().getMessage());
        }
        // Call start now.
        internalScheduler.schedule(NO_DELAY, new Runnable() {
          @Override
          public void run() {
            startInternal(serializedState);
          }
        });
      }
    });
  }

  /**
   * Converts an operation type {@code regStatus} to a
   * {@link InvalidationListener.RegistrationState}.
   */
  private static InvalidationListener.RegistrationState convertOpTypeToRegState(
      RegistrationStatus regStatus) {
    InvalidationListener.RegistrationState regState =
        regStatus.getRegistration().getOpType() == RegistrationP.OpType.REGISTER ?
            InvalidationListener.RegistrationState.REGISTERED :
              InvalidationListener.RegistrationState.UNREGISTERED;
    return regState;
  }

  @Override
  public void toCompactString(TextBuilder builder) {
    builder.appendFormat("Client: %s, %s", applicationClientId,
        CommonProtoStrings2.toLazyCompactString(clientToken));
  }

  /**
   * Sets the nonce to {@code newNonce}.
   * <p>
   * REQUIRES: {@code newNonce} be null or {@link #clientToken} be null.
   * The goal is to ensure that a nonce is never set unless there is no
   * client token, unless the nonce is being cleared.
   */
  private void setNonce(ByteString newNonce) {
    Preconditions.checkState((newNonce == null) || (clientToken == null),
        "Tried to set nonce with existing token %s", clientToken);
    this.nonce = newNonce;
  }

  /**
   * Sets the clientToken to {@code newClientToken}.
   * <p>
   * REQUIRES: {@code newClientToken} be null or {@link #nonce} be null.
   * The goal is to ensure that a token is never set unless there is no
   * nonce, unless the token is being cleared.
   */
  private void setClientToken(ByteString newClientToken) {
    Preconditions.checkState((newClientToken == null) || (nonce == null),
        "Tried to set token with existing nonce %s", nonce);

    // If the ticl is in the process of being started and we are getting a new token (either from
    // persistence or from the server, start the ticl and inform the application.
    boolean finishStartingTicl = !ticlState.isStarted() &&
       (clientToken == null) && (newClientToken != null);
    this.clientToken = newClientToken;

    if (newClientToken != null) {
      // Token control message succeeded - reset the network delay so that the next time we acquire
      // a token, the delay starts from the original value.
      tokenExponentialBackoff.reset(config.networkTimeoutDelayMs);
    }

    if (finishStartingTicl) {
      finishStartingTiclAndInformListener();
    }
  }

  /** Start the ticl and inform the listener that it is ready. */
  private void finishStartingTiclAndInformListener() {
    Preconditions.checkState(!ticlState.isStarted());
    ticlState.start();
    listener.ready(this);

    // We are not currently persisting our registration digest, so regardless of whether or not
    // we are restarting from persistent state, we need to query the application for all of
    // its registrations.
    listener.reissueRegistrations(InvalidationClientImpl.this, RegistrationManager.EMPTY_PREFIX, 0);
    logger.info("Ticl started: %s", this);
  }
}
