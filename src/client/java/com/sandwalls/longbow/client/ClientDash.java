package com.sandwalls.longbow.client;

/** 客户端本地疾行档位（0=关，1=马速，2=鞘翅速），用于 HUD 显示。 */
public final class ClientDash {
	private static int gear = 0;

	private ClientDash() {}

	public static void set(int value) {
		gear = Math.max(0, Math.min(2, value));
	}

	public static int gear() {
		return gear;
	}

	public static boolean isActive() {
		return gear > 0;
	}
}
