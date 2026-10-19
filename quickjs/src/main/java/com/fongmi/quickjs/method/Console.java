package com.fongmi.quickjs.method;

import com.github.catvod.crawler.DebugEvent;
import com.github.catvod.crawler.DebugLogStore;
import com.orhanobut.logger.Logger;
import com.whl.quickjs.wrapper.QuickJSContext;

/**
 * Bridges the JS engine's {@code console.*} into the in-app debug log.
 *
 * <p>Historically these calls only reached logcat, so a spider's own
 * {@code console.log(...)} was invisible from the log viewer — which is exactly where
 * people look when a spider returns nothing.
 */
public class Console implements QuickJSContext.Console {

    private static final String TAG = "quickjs";

    @Override
    public void log(String info) {
        Logger.t(TAG).d(info);
        record("log", info);
    }

    @Override
    public void info(String info) {
        Logger.t(TAG).i(info);
        record("info", info);
    }

    @Override
    public void warn(String info) {
        Logger.t(TAG).w(info);
        record("warn", info);
    }

    @Override
    public void error(String info) {
        Logger.t(TAG).e(info);
        record("error", info);
    }

    private void record(String level, String info) {
        if (!DebugLogStore.isEnabled()) return;
        DebugLogStore.addEvent(DebugEvent.console(level, info).lang(DebugEvent.LANG_JS));
    }
}
