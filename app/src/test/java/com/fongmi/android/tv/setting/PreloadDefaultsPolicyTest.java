package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreloadDefaultsPolicyTest {

    @Test
    public void mpvAndIjkKeepConservativePreloadDefaults() {
        for (int profile : new int[]{PlaybackPerformanceSetting.PROFILE_AUTO, PlaybackPerformanceSetting.PROFILE_RECOMMENDED, PlaybackPerformanceSetting.PROFILE_COMPATIBLE, PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT}) {
            assertEquals(1, KernelPerformanceSetting.preloadThreadsForPreset(PlayerSetting.MPV, profile));
            assertEquals(20, KernelPerformanceSetting.preloadTimeForPreset(PlayerSetting.MPV, profile));
            assertEquals(1, KernelPerformanceSetting.preloadThreadsForPreset(PlayerSetting.IJK, profile));
            assertEquals(20, KernelPerformanceSetting.preloadTimeForPreset(PlayerSetting.IJK, profile));
        }
    }

    @Test
    public void exoUsesTunedPreloadDefaults() {
        for (int profile : new int[]{PlaybackPerformanceSetting.PROFILE_AUTO, PlaybackPerformanceSetting.PROFILE_RECOMMENDED, PlaybackPerformanceSetting.PROFILE_COMPATIBLE, PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT}) {
            assertEquals(2, KernelPerformanceSetting.preloadThreadsForPreset(PlayerSetting.EXO, profile));
            assertEquals(120, KernelPerformanceSetting.preloadTimeForPreset(PlayerSetting.EXO, profile));
        }
    }

    @Test
    public void migrationPreservesCustomProfileValues() {
        assertTrue(PlaybackPerformanceSetting.shouldMigratePreloadDefaults(PlaybackPerformanceSetting.PROFILE_RECOMMENDED));
        assertTrue(PlaybackPerformanceSetting.shouldMigratePreloadDefaults(PlaybackPerformanceSetting.PROFILE_COMPATIBLE));
        assertTrue(PlaybackPerformanceSetting.shouldMigratePreloadDefaults(PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT));
        assertTrue(PlaybackPerformanceSetting.shouldMigratePreloadDefaults(PlaybackPerformanceSetting.PROFILE_AUTO));
        assertFalse(PlaybackPerformanceSetting.shouldMigratePreloadDefaults(PlaybackPerformanceSetting.PROFILE_CUSTOM));
    }

    @Test
    public void deepPreloadOptionsRemainBoundedAndIncludeWholeMedia() {
        assertEquals(9, PreloadSetting.getPreloadSizeOptionCount());
        assertEquals(32_768, PreloadSetting.getPreloadSizeMbAt(8));
        assertEquals(7, PreloadSetting.getPreloadAheadOptionCount());
        assertEquals(PreloadSetting.WHOLE_MEDIA_AHEAD_SECONDS,
                PreloadSetting.getPreloadAheadSecondsAt(6));
        assertEquals(300, PreloadSetting.DEFAULT_AHEAD_SECONDS);
        assertEquals(2, PreloadSetting.getPausePreloadOptionCount());
        assertEquals(PreloadSetting.PAUSE_PRELOAD_ALWAYS,
                PreloadSetting.getPausePreloadPolicyAt(0));
        assertEquals(PreloadSetting.PAUSE_PRELOAD_WIFI,
                PreloadSetting.getPausePreloadPolicyAt(1));
        assertEquals(PreloadSetting.PAUSE_PRELOAD_ALWAYS,
                PreloadSetting.DEFAULT_PAUSE_PRELOAD);
    }
}
