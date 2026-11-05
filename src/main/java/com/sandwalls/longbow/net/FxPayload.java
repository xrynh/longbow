package com.sandwalls.longbow.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S2C：把一个特效点（拖尾光点或命中六芒星）发给客户端渲染。 */
public record FxPayload(int kind, double x, double y, double z, double dx, double dy, double dz, int color, float radius) implements CustomPacketPayload {
	public static final int TRAIL = 0;
	public static final int BURST = 1;
	public static final int CAST = 2;

	public static final Type<FxPayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath("longbow", "fx"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FxPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeVarInt(payload.kind());
			buf.writeDouble(payload.x);
			buf.writeDouble(payload.y);
			buf.writeDouble(payload.z);
			buf.writeDouble(payload.dx);
			buf.writeDouble(payload.dy);
			buf.writeDouble(payload.dz);
			buf.writeInt(payload.color);
			buf.writeFloat(payload.radius);
		},
		buf -> new FxPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readInt(), buf.readFloat())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
