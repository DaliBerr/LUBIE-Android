package Aquin.lubie

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    /**
     * Summary: Verifies OpenCV initializes in the instrumented app process.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun openCv_smokeTest() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Aquin.lubie", appContext.packageName)
        assertTrue(OpenCVLoader.initLocal())
    }
}
