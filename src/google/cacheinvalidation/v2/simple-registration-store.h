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

// Simple, map-based implementation of DigestStore.

#ifndef GOOGLE_CACHEINVALIDATION_V2_SIMPLE_REGISTRATION_STORE_H_
#define GOOGLE_CACHEINVALIDATION_V2_SIMPLE_REGISTRATION_STORE_H_

#include <map>

#include "google/cacheinvalidation/v2/string_util.h"
#include "google/cacheinvalidation/v2/client-protocol-namespace-fix.h"
#include "google/cacheinvalidation/v2/digest-function.h"
#include "google/cacheinvalidation/v2/digest-store.h"

namespace invalidation {

using INVALIDATION_STL_NAMESPACE::map;

class SimpleRegistrationStore : public DigestStore<ObjectIdP> {
 public:
  explicit SimpleRegistrationStore(DigestFunction* digest_function)
      : digest_function_(digest_function) {
    RecomputeDigest();
  }

  virtual ~SimpleRegistrationStore() {}

  virtual void Add(const ObjectIdP& oid);

  virtual void Add(const vector<ObjectIdP>& oids);

  virtual void Remove(const ObjectIdP& oid);

  virtual void Remove(const vector<ObjectIdP>& oids);

  virtual void RemoveAll(vector<ObjectIdP>* oids);

  virtual bool Contains(const ObjectIdP& oid);

  virtual int size() {
    return registrations_.size();
  }

  virtual string GetDigest() {
    return digest_;
  }

  virtual void GetElements(const string& oid_digest_prefix, int prefix_len,
                           vector<ObjectIdP>* result);

  virtual string ToString() {
    return StringPrintf("SimpleRegistrationStore: %d registrations",
                        static_cast<int>(registrations_.size()));
  }

 private:
  /* Recomputes the digests over all objects and sets this.digest. */
  void RecomputeDigest();

  /* All the registrations in the store mappd from the digest to the ibject id.
   */
  map<string, ObjectIdP> registrations_;

  /* The function used to compute digests of objects. */
  DigestFunction* digest_function_;

  /* The memoized digest of all objects in registrations. */
  string digest_;
};

}  // namespace invalidation

#endif  // GOOGLE_CACHEINVALIDATION_V2_SIMPLE_REGISTRATION_STORE_H_
