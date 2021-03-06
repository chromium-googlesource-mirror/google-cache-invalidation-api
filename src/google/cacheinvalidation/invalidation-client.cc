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

#include "google/cacheinvalidation/invalidation-client.h"
#include "google/cacheinvalidation/invalidation-client-impl.h"

namespace invalidation {

InvalidationClient* InvalidationClient::Create(
    SystemResources* resources, const ClientType& client_type,
    const string& application_name, const string& client_info,
    InvalidationListener* listener) {
  ClientConfig config;
  return new InvalidationClientImpl(
      resources, client_type, application_name, client_info, config, listener);
}

}  // namespace invalidation
