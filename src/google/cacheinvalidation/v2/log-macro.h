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

// A simple logging macro specifically for the invalidation client library.

#ifndef GOOGLE_CACHEINVALIDATION_V2_LOG_MACRO_H_
#define GOOGLE_CACHEINVALIDATION_V2_LOG_MACRO_H_

#define TLOG(logger, level, str, ...)                                   \
  logger->Log(Logger::level ## _LEVEL, __FILE__, __LINE__, str, ##__VA_ARGS__);

#endif  // GOOGLE_CACHEINVALIDATION_V2_LOG_MACRO_H_
