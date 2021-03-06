package ru.esmukov.kpfu.lightningrodandroidvpnpoc.packetfilter.l2;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import ru.esmukov.kpfu.lightningrodandroidvpnpoc.AddressMask;
import ru.esmukov.kpfu.lightningrodandroidvpnpoc.SocatServerConnectionInfo;
import ru.esmukov.kpfu.lightningrodandroidvpnpoc.osi.l2.EthernetHeader;
import ru.esmukov.kpfu.lightningrodandroidvpnpoc.osi.l3.ArpHeader;
import ru.esmukov.kpfu.lightningrodandroidvpnpoc.osi.l3.Ip4Header;

/**
 * Created by kostya on 16/12/2016.
 */

/**
 * Resolves MAC addresses (both remote and local).
 * Maintains a queue of pending IP4 packets which are waiting for destination MAC address resolution.
 * <p>
 * Non thread safe.
 */
class MacResolver {

    private SocatServerConnectionInfo.InterfaceInfo mInterfaceInfo;

    private Map<Integer, Long> mIpToMac = new HashMap<>(); // ARP table
    private Deque<CustomPacket> mPacketDeque = new LinkedList<>(); // packets ready to be sent
    private Map<Integer, Deque<CustomPacket>> mIpToPacketsWaitingForMacResolution = new HashMap<>();

    MacResolver(SocatServerConnectionInfo.InterfaceInfo interfaceInfo) {
        mInterfaceInfo = interfaceInfo;
    }

    long getLocalMacAddress() {
        return mInterfaceInfo.getLocalMacAddress();
    }

    Long getDestinationMacAddressByL3IpPacket(ByteBuffer ip4Packet) {
        return mIpToMac.get(Ip4Header.getDestinationIp(ip4Packet));
    }

    void resolveMacAndQueueL3IpPacket(ByteBuffer ip4Packet) {
        int destinationIp = Ip4Header.getDestinationIp(ip4Packet);

        mPacketDeque.add(CustomPacket.fromL2Packet(
                ArpHeader.createRequestFrame(getLocalMacAddress(),
                        Ip4Header.getSourceIp(ip4Packet), destinationIp)
        ));

        if (!mIpToPacketsWaitingForMacResolution.containsKey(destinationIp)) {
            mIpToPacketsWaitingForMacResolution.put(destinationIp, new LinkedList<CustomPacket>());
        }

        mIpToPacketsWaitingForMacResolution.get(destinationIp).add(
                CustomPacket.fromL3Packet(ip4Packet.duplicate())
        );
    }

    CustomPacket pollPacketFromQueue() {
        return mPacketDeque.pollFirst();
    }

    void processIncomingArpPacket(ByteBuffer l3ArpPacket) throws Exception {
        ArpHeader.ArpPacket arpPacket = ArpHeader.parsePacket(l3ArpPacket);

        if (arpPacket instanceof ArpHeader.ArpRequest) {
            ArpHeader.ArpRequest arpRequest = (ArpHeader.ArpRequest) arpPacket;

            for (AddressMask addressMask : mInterfaceInfo.getLocalInterfaceAddressList()) {
                if (addressMask.getAddressInt() == arpRequest.getRequestedIp()) {
                    // this is a request for us - send a reply
                    mPacketDeque.add(CustomPacket.fromL2Packet(
                            ArpHeader.createResponseFrame(getLocalMacAddress(), arpRequest)
                    ));
                    break;
                }
            }

            addIpToMacPair(arpRequest.getRequesterIp(), arpRequest.getRequesterMac());
        }

        if (arpPacket instanceof ArpHeader.ArpReply) {
            ArpHeader.ArpReply arpReply = (ArpHeader.ArpReply) arpPacket;

            addIpToMacPair(arpReply.getReplyerIp(), arpReply.getReplyerMac());
        }
    }

    private void addIpToMacPair(int ip, long mac) {
        if (mac == EthernetHeader.BROADCAST_MAC)
            return;

        mIpToMac.put(ip, mac);

        if (mIpToPacketsWaitingForMacResolution.containsKey(ip)) {
            mPacketDeque.addAll(mIpToPacketsWaitingForMacResolution.get(ip));
            mIpToPacketsWaitingForMacResolution.remove(ip);
        }
    }

    boolean shouldFrameBeAccepted(long destinationMac) {
        return destinationMac == EthernetHeader.BROADCAST_MAC
                || destinationMac == getLocalMacAddress();
    }

    static class CustomPacket {
        private ByteBuffer mPacket;
        private boolean mIsL3;

        private CustomPacket(ByteBuffer packet, boolean isL3) {
            mPacket = packet;
            mIsL3 = isL3;
        }

        static CustomPacket fromL3Packet(ByteBuffer packet) {
            return new CustomPacket(packet, true);
        }

        static CustomPacket fromL2Packet(ByteBuffer packet) {
            return new CustomPacket(packet, false);
        }

        ByteBuffer getPacket() {
            return mPacket;
        }

        boolean isL3() {
            return mIsL3;
        }
    }

}
