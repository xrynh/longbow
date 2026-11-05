package com.sandwalls.longbow.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S：切换「探矿模式」——破坏时跳过矿物方块。 */
public record ProspectPayload(boolean enabled) implements CustomPacketPayload {
	public static final Type<ProspectPayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath("longbow", "prospect"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ProspectPayload> CODEC = StreamCodec.of(
		(buf, payload) -> buf.writeBoolean(payload.enabled()),
		buf -> new ProspectPayload(buf.readBoolean())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
