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

package com.google.ipc.invalidation.external.client.android;

import com.google.ipc.invalidation.external.client.InvalidationClient;

import android.accounts.Account;

/**
 * Extends the {@link InvalidationClient} interface to add Android-specific
 * client functionality.
 *
 */
public interface AndroidInvalidationClient extends InvalidationClient {

  /**
   * Returns the {@link Account} associated with the client.
   */
  Account getAccount();

  /**
   * Returns the authenticate type that is used to authenticate the client.
   */
  String getAuthType();

  /*
   * Returns the client key that uniquely identifies this client.   The client key can be passed
   * to {@link AndroidClientFactory#resume} to resume processing with the client instance that
   * is identified by this.
   */
  public String getClientKey();
}
