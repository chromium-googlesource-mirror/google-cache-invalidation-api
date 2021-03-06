// Copyright 2011 Google Inc.
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

// An implementation of the Scheduler interface for unit testing (in a
// single-threaded environment).

#ifndef GOOGLE_CACHEINVALIDATION_V2_TEST_DETERMINISTIC_SCHEDULER_H_
#define GOOGLE_CACHEINVALIDATION_V2_TEST_DETERMINISTIC_SCHEDULER_H_

#include <queue>
#include <string>
#include <utility>

#include "google/cacheinvalidation/callback.h"
#include "google/cacheinvalidation/v2/logging.h"
#include "google/cacheinvalidation/v2/string_util.h"
#include "google/cacheinvalidation/v2/system-resources.h"
#include "google/cacheinvalidation/v2/time.h"

namespace invalidation {

// An entry in the work queue.  Ensures that tasks don't run until their
// scheduled time, and for a given time, they run in the order in which they
// were enqueued.
struct TaskEntry {
  TaskEntry(Time time, bool immediate, int64 id, Closure* task)
      : time(time), immediate(immediate), id(id), task(task) {}

  bool operator<(const TaskEntry& other) const {
    // Priority queue returns *largest* element first.
    return (time > other.time) ||
        ((time == other.time) && (id > other.id));
  }
  Time time;  // the time at which to run
  bool immediate;  // whether the task was scheduled "immediately"
  int64 id;  // the order in which this task was enqueued
  Closure* task;  // the task to be run
};

class DeterministicScheduler : public Scheduler {
 public:
  DeterministicScheduler()
      : current_id_(0), started_(false), stopped_(false),
        running_internal_(false) {}

  ~DeterministicScheduler() {
    StopScheduler();
  }

  virtual Time GetCurrentTime() const {
    return current_time_;
  }

  void StartScheduler() {
    started_ = true;
  }

  void StopScheduler() {
    stopped_ = true;
    while (!work_queue_.empty()) {
      TaskEntry top_elt = work_queue_.top();
      work_queue_.pop();
      // If the task has expired or was scheduled with ScheduleImmediately(),
      // run it.
      if (top_elt.immediate || (top_elt.time <= GetCurrentTime())) {
        top_elt.task->Run();
      }
      delete top_elt.task;
    }
    while (!listener_work_queue_.empty()) {
      // All listener tasks were to run immediately, so run them all.
      Closure* task = listener_work_queue_.front();
      listener_work_queue_.pop();
      task->Run();
      delete task;
    }
  }

  virtual void Schedule(TimeDelta delay, Closure* task) {
    CHECK(IsCallbackRepeatable(task));
    CHECK(started_);
    if (!stopped_) {
      work_queue_.push(TaskEntry(GetCurrentTime() + delay, false, current_id_++,
                                 task));
    } else {
      delete task;
    }
  }

  virtual bool IsRunningOnThread() const {
    return running_internal_;
  }

  void SetTime(Time new_time) {
    current_time_ = new_time;
  }

  void ModifyTime(TimeDelta delta_time) {
    current_time_ += delta_time;
  }

  // Runs all the work in the queue that should be executed by the current time.
  // Note that tasks run may enqueue additional immediate tasks, and this call
  // won't return until they've completed as well.  While these tasks are
  // running, the running_internal_ flag is set, so IsRunningOnInternalThread()
  // will return true.
  void RunReadyTasks() {
    running_internal_ = true;
    while (RunNextTask()) {
      continue;
    }
    running_internal_ = false;
  }

 private:
  // Attempts to run a task, returning true is there was a task to run.
  bool RunNextTask() {
    if (!work_queue_.empty()) {
      // The queue is not empty, so get the first task and see if its scheduled
      // execution time has passed.
      TaskEntry top_elt = work_queue_.top();
      if (top_elt.time <= GetCurrentTime()) {
        // The task is scheduled to run in the past or present, so remove it
        // from the queue and run the task.
        work_queue_.pop();
        top_elt.task->Run();
        delete top_elt.task;
        return true;
      }
    }
    return false;
  }

  // The current time, which may be set by the test.
  Time current_time_;

  // The id number of the next task.
  uint64 current_id_;

  // Whether or not the scheduler has been started.
  bool started_;

  // Whether or not the scheduler has been stopped.
  bool stopped_;

  // Whether or not we're currently running internal tasks from the internal
  // queue.
  bool running_internal_;

  // A priority queue on which the actual tasks are enqueued.
  std::priority_queue<TaskEntry> work_queue_;

  // A simple queue for the listener tasks.
  std::queue<Closure*> listener_work_queue_;
};

}  // namespace invalidation

#endif  // GOOGLE_CACHEINVALIDATION_V2_TEST_DETERMINISTIC_SCHEDULER_H_
