package me.wolfii.haveiplayedwith.observe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Stable ids for where a sighting happened. Remote servers use the address;
 * local worlds use {@code world/{worldname}}.
 */
public final class PlayLocations {
    public static final String LOCAL_PREFIX = "world/";
    private static final String DEFAULT_PORT = "25565";

    private PlayLocations() {
    }

    public static String current(Minecraft client) {
        if (client.hasSingleplayerServer()) {
            IntegratedServer server = client.getSingleplayerServer();
            if (server != null) {
                String name = worldName(server);
                if (name != null) {
                    return localWorld(name);
                }
            }
        }
        String remote = remoteFrom(client.getCurrentServer());
        if (remote != null) {
            return remote;
        }
        ClientPacketListener connection = client.getConnection();
        if (connection != null) {
            remote = remoteFrom(connection.getServerData());
            if (remote != null) {
                return remote;
            }
            return fromConnection(connection);
        }
        return null;
    }

    public static String localWorld(String worldName) {
        String cleaned = sanitize(worldName);
        if (cleaned.isEmpty()) {
            cleaned = "unnamed";
        }
        return LOCAL_PREFIX + cleaned;
    }

    public static String remoteServer(String address) {
        String trimmed = sanitize(address);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close > 0) {
                String host = trimmed.substring(0, close + 1).toLowerCase(Locale.ROOT);
                String rest = trimmed.substring(close + 1);
                if (rest.isEmpty() || (":" + DEFAULT_PORT).equals(rest)) {
                    return host;
                }
                return host + rest;
            }
        }
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0 && trimmed.indexOf(':') == colon) {
            String host = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
            String port = trimmed.substring(colon + 1);
            if (DEFAULT_PORT.equals(port)) {
                return host;
            }
            return host + ":" + port;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String worldName(IntegratedServer server) {
        WorldData data = server.getWorldData();
        if (data != null) {
            String name = data.getLevelName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path fileName = root == null ? null : root.getFileName();
        return fileName == null ? null : fileName.toString();
    }

    private static String remoteFrom(ServerData remote) {
        if (remote == null) {
            return null;
        }
        String address = addressOf(remote);
        return address == null ? null : remoteServer(address);
    }

    private static String addressOf(ServerData remote) {
        if (remote.ip != null && !remote.ip.isBlank()) {
            return remote.ip;
        }
        if (remote.name != null && !remote.name.isBlank()) {
            return remote.name;
        }
        return null;
    }

    private static String fromConnection(ClientPacketListener connection) {
        SocketAddress address = connection.getConnection().getRemoteAddress();
        if (address instanceof InetSocketAddress inet) {
            String host = inet.getHostString();
            int port = inet.getPort();
            if (host == null || host.isBlank()) {
                return null;
            }
            return port > 0 && port != Integer.parseInt(DEFAULT_PORT)
                ? remoteServer(host + ":" + port)
                : remoteServer(host);
        }
        return null;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip();
    }
}
