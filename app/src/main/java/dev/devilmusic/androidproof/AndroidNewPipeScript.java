// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof;
import org.mozilla.javascript.*;

/** Restrict upstream Rhino evaluation to safe classes, with an instruction deadline. */
final class AndroidNewPipeScript extends ContextFactory {
    private final ThreadLocal<Long> deadline=new ThreadLocal<>();
    @Override protected Context makeContext() {
        Context cx=super.makeContext();
        cx.setInterpretedMode(true);
        cx.setInstructionObserverThreshold(10000);
        cx.setClassShutter(name->false);
        deadline.set(System.nanoTime()+2_000_000_000L);
        return cx;
    }
    @Override protected void observeInstructionCount(Context cx,int count) {
        if(System.nanoTime()>deadline.get())throw new EvaluatorException("script_deadline");
    }
    @Override protected void onContextReleased(Context cx) { deadline.remove(); super.onContextReleased(cx); }
    private static boolean installed; static synchronized void installOnce() { if (!installed) { ContextFactory.initGlobal(new AndroidNewPipeScript()); installed=true; } }
}
