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

// Various constants common to clients and servers used in version 2 of the
// Ticl.

#ifndef GOOGLE_CACHEINVALIDATION_V2_CONSTANTS_H_
#define GOOGLE_CACHEINVALIDATION_V2_CONSTANTS_H_

namespace invalidation {

class Constants {
 public:
  /* Major version of the client library. */
  static const int kClientMajorVersion;

  /* Minor version of the client library. */
  static const int kClientMinorVersion;

  /* Major version of the protocol between the client and the server. */
  static const int kProtocolMajorVersion;

  /* Minor version of the protocol between the client and the server. */
  static const int kProtocolMinorVersion;
};

}  // namespace invalidation

#endif  // GOOGLE_CACHEINVALIDATION_V2_CONSTANTS_H_
