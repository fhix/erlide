package org.erlide.core.backend.events;

import java.util.Collection;

import org.erlide.jinterface.ErlLogger;

public abstract class EventHandler {

    public final void handleEvents(final Collection<ErlangEvent> events) {
        for (final ErlangEvent event : events) {
            handleEvent(event);
        }
    }

    public void handleEvent(final ErlangEvent event) {
        if (event == null) {
            return;
        }
        try {
            doHandleEvent(event);
        } catch (final Exception e) {
            ErlLogger.debug(e);
            // ignore unrecognized messages
        }
    }

    protected abstract void doHandleEvent(ErlangEvent event) throws Exception;

}
