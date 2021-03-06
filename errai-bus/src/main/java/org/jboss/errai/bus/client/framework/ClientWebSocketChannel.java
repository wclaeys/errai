/*
 * Copyright 2011 JBoss, by Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.bus.client.framework;

/**
 * @author Mike Brock
 */
public class ClientWebSocketChannel {
  public static native Object attemptWebSocketConnect(final ClientMessageBusImpl bus, final String websocketAddr) /*-{
    var socket;
    if (window.WebSocket) {
      socket = new WebSocket(websocketAddr);

      socket.onmessage = function (event) {
        bus.@org.jboss.errai.bus.client.framework.ClientMessageBusImpl::procPayload(Ljava/lang/String;)(event.data);
      };

      socket.onopen = function (event) {
        bus.@org.jboss.errai.bus.client.framework.ClientMessageBusImpl::attachWebSocketChannel(Ljava/lang/Object;)(socket);
      };
      socket.onclose = function (event) {
         // should probably do something here.
    };
      return socket;
    } else {
      return "NotSupportedByBrowser";
    }
  }-*/;

  public static native boolean transmitToSocket(final Object socket, final String text) /*-{
    if (socket.readyState == WebSocket.OPEN) {
      socket.send(text);
      return true;
    }
    else {
      return false;
    }
  }-*/;
}
