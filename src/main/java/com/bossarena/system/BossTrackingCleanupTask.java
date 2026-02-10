package com.bossarena.system;

public final class BossTrackingCleanupTask implements Runnable {

  private final BossTrackingSystem trackingSystem;

  public BossTrackingCleanupTask(BossTrackingSystem trackingSystem) {
    this.trackingSystem = trackingSystem;
  }

  @Override
  public void run() {
    trackingSystem.cleanupExpired();
  }

  public void runOnce() {
    run();
  }
}
