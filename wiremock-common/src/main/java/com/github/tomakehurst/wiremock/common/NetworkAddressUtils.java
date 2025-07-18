/*
 * Copyright (C) 2023-2025 Thomas Akehurst
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
package com.github.tomakehurst.wiremock.common;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class NetworkAddressUtils {

  private NetworkAddressUtils() {}

  public static boolean isValidInet4Address(String ip) {
    try {
      return InetAddress.getByName(ip).getHostAddress().equals(ip);
    } catch (UnknownHostException ex) {
      return false;
    }
  }

  public static long ipToLong(InetAddress ipAddress) {
    long resultIP = 0;
    byte[] ipAddressOctets = ipAddress.getAddress();

    for (byte octet : ipAddressOctets) {
      resultIP <<= 8;
      resultIP |= octet & 0xFF;
    }
    return resultIP;
  }
}
