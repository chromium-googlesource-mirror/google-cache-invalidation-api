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

#ifndef GOOGLE_CACHEINVALIDATION_MD5_H_
#define GOOGLE_CACHEINVALIDATION_MD5_H_

namespace invalidation {

void ComputeMd5Digest(const string& data, string* digest);
#error ComputeMd5Digest unimplemented
}  // namespace invalidation

#endif  // GOOGLE_CACHEINVALIDATION_MD5_H_
