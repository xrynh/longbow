package com.sandwalls.longbow.logic;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S：客户端把"当前锁定的实体 id"告诉服务端（-1 表示解锁）。 */
public record LockPayload(int entityId) implements CustomPacketPayload {
	public static final Type<LockPayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath("longbow", "lock"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LockPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, LockPayload::entityId,
		LockPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
