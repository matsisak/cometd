package org.cometd.client.transport;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.client.BayeuxClient;

/**
 * @version $Revision$ $Date$
 */
public interface ClientTransport extends Transport
{
    void addListener(TransportListener listener);

    void removeListener(TransportListener listener);

    boolean accept(String bayeuxVersion);

    void init(BayeuxClient bayeux);

    void destroy();

    Message.Mutable newMessage();

    void send(String uri, Message... messages);
}