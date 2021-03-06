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
package org.jboss.errai.enterprise.client.cdi.api;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.errai.bus.client.ErraiBus;
import org.jboss.errai.bus.client.api.Message;
import org.jboss.errai.bus.client.api.MessageCallback;
import org.jboss.errai.bus.client.api.base.CommandMessage;
import org.jboss.errai.bus.client.api.base.MessageBuilder;
import org.jboss.errai.bus.client.framework.ClientMessageBus;
import org.jboss.errai.bus.client.framework.ClientMessageBusImpl;
import org.jboss.errai.bus.client.framework.Subscription;
import org.jboss.errai.common.client.api.WrappedPortable;
import org.jboss.errai.common.client.api.extension.InitVotes;
import org.jboss.errai.common.client.protocols.MessageParts;
import org.jboss.errai.common.client.util.LogUtil;
import org.jboss.errai.enterprise.client.cdi.AbstractCDIEventCallback;
import org.jboss.errai.enterprise.client.cdi.CDICommands;
import org.jboss.errai.enterprise.client.cdi.CDIEventTypeLookup;
import org.jboss.errai.enterprise.client.cdi.CDIProtocol;

/**
 * CDI client interface.
 *
 * @author Heiko Braun <hbraun@redhat.com>
 * @author Christian Sadilek <csadilek@redhat.com>
 * @author Mike Brock <cbrock@redhat.com>
 */
public class CDI {
  public static final String CDI_SUBJECT_PREFIX = "cdi.event:";
  public static final String SERVER_DISPATCHER_SUBJECT = CDI_SUBJECT_PREFIX + "Dispatcher";
  public static final String CLIENT_DISPATCHER_SUBJECT = CDI_SUBJECT_PREFIX + "ClientDispatcher";
  private static final String CLIENT_ALREADY_FIRED_RESOURCE = CDI_SUBJECT_PREFIX + "AlreadyFired";

  private static final Set<String> remoteEvents = new HashSet<String>();
  private static boolean active = false;
  private static final List<DeferredEvent> deferredEvents = new ArrayList<DeferredEvent>();
  private static final List<Runnable> postInitTasks = new ArrayList<Runnable>();

  private static Map<String, List<MessageCallback>> eventObservers = new HashMap<String, List<MessageCallback>>();
  private static Map<String, Collection<String>> lookupTable = Collections.emptyMap();

  public static final MessageCallback ROUTING_CALLBACK = new MessageCallback() {
    @Override
    public void callback(final Message message) {
      consumeEventFromMessage(message);
    }
  };

  public static String getSubjectNameByType(final String typeName) {
    return CDI_SUBJECT_PREFIX + typeName;
  }

  /**
   * Should only be called by bootstrapper for testing purposes.
   */
  public void __resetSubsystem() {
    for (final String eventType : new HashSet<String>(((ClientMessageBus) ErraiBus.get()).getAllRegisteredSubjects())) {
      if (eventType.startsWith(CDI_SUBJECT_PREFIX)) {
        ErraiBus.get().unsubscribeAll(eventType);
      }
    }

    remoteEvents.clear();
    active = false;
    deferredEvents.clear();
    postInitTasks.clear();
    eventObservers.clear();
    lookupTable = Collections.emptyMap();
  }

  public void initLookupTable(final CDIEventTypeLookup lookup) {
    lookupTable = lookup.getTypeLookupMap();
  }

  /**
   * Return a list of string representations for the qualifiers.
   *
   * @param qualifiers
   *
   * @return
   */
  public static Set<String> getQualifiersPart(final Annotation[] qualifiers) {
    Set<String> qualifiersPart = null;
    if (qualifiers != null) {
      for (final Annotation qualifier : qualifiers) {
        if (qualifiersPart == null)
          qualifiersPart = new HashSet<String>(qualifiers.length);

        qualifiersPart.add(qualifier.annotationType().getName());
      }
    }
    return qualifiersPart == null ? Collections.<String>emptySet() : qualifiersPart;

  }

  public static void fireEvent(final Object payload, final Annotation... qualifiers) {
    fireEvent(false, payload, qualifiers);
  }


  public static void fireEvent(final boolean local,
                               final Object payload,
                               final Annotation... qualifiers) {

    if (payload == null) return;

    final Object beanRef;
    if (payload instanceof WrappedPortable) {
      beanRef = ((WrappedPortable) payload).unwrap();
      if (beanRef == null) return;
    }
    else {
      beanRef = payload;
    }
    
    if (!local && !active) {
      deferredEvents.add(new DeferredEvent(beanRef, qualifiers));
      return;
    }

    final Map<String, Object> messageMap = new HashMap<String, Object>();
    messageMap.put(MessageParts.CommandType.name(), CDICommands.CDIEvent.name());
    messageMap.put(CDIProtocol.BeanType.name(), beanRef.getClass().getName());
    messageMap.put(CDIProtocol.BeanReference.name(), beanRef);
    messageMap.put(CDIProtocol.FromClient.name(), "1");

    if (qualifiers != null && qualifiers.length > 0) {
      messageMap.put(CDIProtocol.Qualifiers.name(), getQualifiersPart(qualifiers));
    }

    consumeEventFromMessage(CommandMessage.createWithParts(messageMap));

    if (isRemoteCommunicationEnabled() && remoteEvents.contains(beanRef.getClass().getName())) {
      messageMap.put(MessageParts.ToSubject.name(), SERVER_DISPATCHER_SUBJECT);
      ErraiBus.get().send(CommandMessage.createWithParts(messageMap));
    }
  }

  public static Subscription subscribeLocal(final String eventType, final AbstractCDIEventCallback callback) {
    if (!eventObservers.containsKey(eventType)) {
      eventObservers.put(eventType, new ArrayList<MessageCallback>());
    }
    eventObservers.get(eventType).add(callback);

    return new Subscription() {
      @Override
      public void remove() {
        unsubscribe(eventType, callback);
      }
    };
  }


  public static Subscription subscribe(final String eventType, final AbstractCDIEventCallback callback) {

    if (isRemoteCommunicationEnabled()) {
      MessageBuilder.createMessage()
          .toSubject(CDI.SERVER_DISPATCHER_SUBJECT)
          .command(CDICommands.RemoteSubscribe)
          .with(CDIProtocol.BeanType, eventType)
          .with(CDIProtocol.Qualifiers, callback.getQualifiers())
          .noErrorHandling().sendNowWith(ErraiBus.get());
    }

    return subscribeLocal(eventType, callback);
  }

  private static void unsubscribe(final String eventType, final AbstractCDIEventCallback callback) {
    if (eventObservers.containsKey(eventType)) {
      eventObservers.get(eventType).remove(callback);
      if (eventObservers.get(eventType).isEmpty()) {
        eventObservers.remove(eventType);
      }

      if (isRemoteCommunicationEnabled()) {
        MessageBuilder.createMessage()
            .toSubject(CDI.SERVER_DISPATCHER_SUBJECT)
            .command(CDICommands.RemoteUnsubscribe)
            .with(CDIProtocol.BeanType, eventType)
            .with(CDIProtocol.Qualifiers, callback.getQualifiers())
            .noErrorHandling().sendNowWith(ErraiBus.get());
      }
    }
  }


  public static void consumeEventFromMessage(final Message message) {
    final String beanType = message.get(String.class, CDIProtocol.BeanType);

    _fireEvent(beanType, message);

    if (lookupTable.containsKey(beanType)) {
      for (final String superType : lookupTable.get(beanType)) {
        _fireEvent(superType, message);
      }
    }
  }

  private static void _fireEvent(final String beanType, final Message message) {
    if (eventObservers.containsKey(beanType)) {
      for (final MessageCallback callback : eventObservers.get(beanType)) {
        fireIfNotFired(callback, message);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void fireIfNotFired(final MessageCallback callback, final Message message) {
    if (!message.hasResource(CLIENT_ALREADY_FIRED_RESOURCE)) {
      message.setResource(CLIENT_ALREADY_FIRED_RESOURCE, new IdentityHashMap<Object, Object>());
    }

    if (!message.getResource(Map.class, CLIENT_ALREADY_FIRED_RESOURCE).containsKey(callback)) {
      callback.callback(message);
      message.getResource(Map.class, CLIENT_ALREADY_FIRED_RESOURCE).put(callback, "");
    }
  }

  public static void addRemoteEventType(final String remoteEvent) {
    remoteEvents.add(remoteEvent);
  }

  public static void addRemoteEventTypes(final String[] remoteEvent) {
    for (final String s : remoteEvent) {
      addRemoteEventType(s);
    }
  }

  public static void addPostInitTask(final Runnable runnable) {
    if (active) {
      runnable.run();
    }
    else {
      postInitTasks.add(runnable);
    }
  }

  public static void removePostInitTasks() {
    postInitTasks.clear();
  }

  public static void activate() {
    if (!active) {
      active = true;
      for (final DeferredEvent o : deferredEvents) {
        fireEvent(o.eventInstance, o.annotations);
      }

      for (final Runnable r : postInitTasks) {
        r.run();
      }

      deferredEvents.clear();

      LogUtil.log("activated CDI eventing subsystem.");
    }
    InitVotes.voteFor(CDI.class);
  }


  static class DeferredEvent {
    final Object eventInstance;
    final Annotation[] annotations;

    DeferredEvent(final Object eventInstance, final Annotation[] annotations) {
      this.eventInstance = eventInstance;
      this.annotations = annotations;
    }
  }

  private static boolean isRemoteCommunicationEnabled() {
    return ((ClientMessageBusImpl) ErraiBus.get()).isRemoteCommunicationEnabled();
  }
}
