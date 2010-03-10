package org.errai.samples.helloworld.server;

import com.google.inject.Inject;
import org.jboss.errai.bus.client.api.Message;
import org.jboss.errai.bus.client.api.MessageCallback;
import org.jboss.errai.bus.client.api.base.MessageBuilder;
import org.jboss.errai.bus.client.framework.MessageBus;
import org.jboss.errai.bus.server.annotations.Service;

@Service
public class ErrorServlet implements MessageCallback {
    private MessageBus bus;

    @Inject
    public ErrorServlet(MessageBus bus) {
        this.bus = bus;
    }

    public void callback(Message message) {

    }
}
