// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import org.junit.Test
import org.junit.Assert.*
class ScriptDeadlineTest {
    @Test fun upstreamRhinoEvaluationHasAnInstructionDeadline() {
        AndroidNewPipeScript.installOnce()
        val start=System.nanoTime()
        try {
            org.schabi.newpipe.extractor.utils.JavaScript.run("function endless(){while(true){}}","endless")
            fail("Unbounded script returned")
        } catch(e:org.mozilla.javascript.EvaluatorException) { assertTrue(e.message.orEmpty().contains("script_deadline")) }
        assertTrue((System.nanoTime()-start)/1_000_000<6000)
    }
}
