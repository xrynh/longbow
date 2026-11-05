package com.sandwalls.longbow.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S：设置「虹」的疾行档位（0=关闭，1=马速，2=鞘翅速）。 */
public record DashPayload(int gear) implements CustomPacketPayload {
	public static final Type<DashPayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath("longbow", "dash"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DashPayload> CODEC = StreamCodec.of(
		(buf, payload) -> buf.writeVarInt(payload.gear()),
		buf -> new DashPayload(buf.readVarInt())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
