package net.lax1dude.eaglercraft.v2_6.plugin.gateway_velocity;

import java.util.concurrent.atomic.AtomicReference;

import com.velocitypowered.api.command.SimpleCommand;

/**
 * {@code /eagler} status command for EaglercraftXVelocity.
 *
 * <p>Reports whether the WebSocket listener is running, the port it is bound
 * to, and how many handshakes were completed this session. Useful to confirm
 * the gateway came up without digging through the proxy log.</p>
 */
public class EaglerCommand implements SimpleCommand {

    private final AtomicReference<EaglerWSListener> listenerRef;

    public EaglerCommand(AtomicReference<EaglerWSListener> listenerRef) {
        this.listenerRef = listenerRef;
    }

    @Override
    public void execute(Invocation invocation) {
        EaglerWSListener l = listenerRef.get();
        if (l == null) {
            invocation.source().sendMessage(
                    net.kyori.adventure.text.Component.text("EaglercraftXVelocity: listener not started"));
            return;
        }
        invocation.source().sendMessage(net.kyori.adventure.text.Component.text(
                "EaglercraftXVelocity " + EaglercraftXVelocity.SERVER_BRAND
                        + " - ws://0.0.0.0:" + l.getPort()
                        + ", " + l.getTotalAccepted() + " handshake(s) this session"));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
