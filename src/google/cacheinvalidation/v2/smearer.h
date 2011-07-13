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

// An abstraction to "smear" values by a given percent. Useful for randomizing
// delays a little bit so that (say) processes do not get synchronized on time
// inadvertently, e.g., a heartbeat task that sends a message every few minutes
// is smeared so that all clients do not end up sending a message at the same
// time. In particular, given a {@code delay}, returns a value that is randomly
// distributed between
// [delay - smearPercent * delay, delay + smearPercent * delay]

#ifndef GOOGLE_CACHEINVALIDATION_V2_SMEARER_H_
#define GOOGLE_CACHEINVALIDATION_V2_SMEARER_H_

#include "google/cacheinvalidation/scoped_ptr.h"
#include "google/cacheinvalidation/random.h"
#include "google/cacheinvalidation/v2/logging.h"

namespace invalidation {

class Smearer {
 public:
  /* Creates a smearer with the given random number generator and default
   * smear percent. random is owned by this after the call.
   */
  explicit Smearer(Random* random) :
    random_(random), smear_fraction_(kDefaultSmearPercent / 100.0) {}

  /* Creates a smearer with the given random number generator.
   * random is owned by this after the call.
   * REQUIRES: 0 < smearPercent <= 100
   */
  Smearer(Random* random, int smear_percent) : random_(random),
          smear_fraction_(smear_percent / 100.0) {
    CHECK((smear_percent > 0) && (smear_percent <= 100));
  }

  /* Given a delay, returns a value that is randomly distributed between
   * (delay - smearPercent * delay, delay + smearPercent * delay)
   */
  TimeDelta GetSmearedDelay(TimeDelta delay) {
    // Get a random number between -1 and 1 and then multiply that by the
    // smear fraction.
    double smear_factor = (2 * random_->RandDouble() - 1.0) * smear_fraction_;
    return TimeDelta::FromMilliseconds(
        delay.InMilliseconds() * (1.0 + smear_factor));
  }

 private:
  /* Default smearing to be done if the caller does not specify any. */
  static const int kDefaultSmearPercent = 20;

  scoped_ptr<Random> random_;

  /* The percentage (0, 1.0] for smearing the delay. */
  double smear_fraction_;
};
}  // namespace invalidation

#endif  // GOOGLE_CACHEINVALIDATION_V2_SMEARER_H_
