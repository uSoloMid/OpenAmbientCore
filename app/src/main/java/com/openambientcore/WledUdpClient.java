package com.openambientcore;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class WledUdpClient {
    private static final int WLED_REALTIME_PORT = 21324;
    private final String host;

    public WledUdpClient(String host) {
        this.host = host == null ? "" : host.trim();
    }

    public void sendDrgb(int[][] colors) {
        if (host.isEmpty() || colors == null || colors.length == 0) return;
        byte[] payload = new byte[2 + colors.length * 3];
        payload[0] = 2;
        payload[1] = 2;
        int p = 2;
        for (int[] color : colors) {
            payload[p++] = (byte) clamp(color[0]);
            payload[p++] = (byte) clamp(color[1]);
            payload[p++] = (byte) clamp(color[2]);
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(50);
            InetAddress address = InetAddress.getByName(host);
            DatagramPacket packet = new DatagramPacket(payload, payload.length, address, WLED_REALTIME_PORT);
            socket.send(packet);
        } catch (Exception ignored) {
        }
    }

    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }
}
