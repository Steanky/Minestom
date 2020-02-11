package fr.themode.minestom.net.packet.server.play;

import fr.themode.minestom.net.packet.PacketWriter;
import fr.themode.minestom.net.packet.server.ServerPacket;
import fr.themode.minestom.net.packet.server.ServerPacketIdentifier;

public class EntityPacket implements ServerPacket {

    public int entityId;

    @Override
    public void write(PacketWriter writer) {
        writer.writeVarInt(entityId);
    }

    @Override
    public int getId() {
        return ServerPacketIdentifier.ENTITY_MOVEMENT;
    }
}
