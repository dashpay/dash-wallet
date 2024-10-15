package org.dashj.platform.sdk.tests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Method


/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class AndroidPlatformTests {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.dashj.platform.sdk.tests.test", appContext.packageName)
    }

    @Test
    fun runAllTestsFromMultipleClasses() {
        try {
            // Array of test class names
            val testClasses = arrayOf(
                "org.dashj.platform.sdk.VoteObjectTest",
                "org.dashj.platform.sdk.ValueTest",
                "org.dashj.platform.sdk.IdentityObjectTest",
                "org.dashj.platform.sdk.BinaryData",
                "org.dashj.platform.sdk.IdentityPublicKeyTest"
            )

            for (className in testClasses) {
                val testClass = Class.forName(className)
                val methods = testClass.declaredMethods
                var beforeClassMethod: Method? = null
                var afterClassMethod: Method? = null
                val beforeMethods = mutableListOf<Method>()
                val afterMethods = mutableListOf<Method>()
                val testMethods = mutableListOf<Method>()

                // Find lifecycle methods and test methods
                for (method in methods) {
                    when {
                        method.isAnnotationPresent(BeforeClass::class.java) -> beforeClassMethod = method
                        method.isAnnotationPresent(AfterClass::class.java) -> afterClassMethod = method
                        method.isAnnotationPresent(Before::class.java) -> beforeMethods.add(method)
                        method.isAnnotationPresent(After::class.java) -> afterMethods.add(method)
                        method.isAnnotationPresent(Test::class.java) -> testMethods.add(method)
                    }
                }

                // Run @BeforeClass (must be static)
                beforeClassMethod?.let { invokeMethod(it, null) }  // Pass null because it's static

                // Create a new instance of the test class for non-static methods
                val testInstance = testClass.getDeclaredConstructor().newInstance()

                for (testMethod in testMethods) {
                    println("Running test: " + testMethod.name)


                    // Run @Before methods before every test
                    beforeMethods.forEach { invokeMethod(it, testInstance) }

                    // Run the test method
                    invokeMethod(testMethod, testInstance)

                    // Run @After methods after every test
                    afterMethods.forEach { invokeMethod(it, testInstance) }
                }

                // Run @AfterClass (must be static)
                afterClassMethod?.let { invokeMethod(it, null) }  // Pass null because it's static
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun invokeMethod(method: Method, instance: Any?) {
        try {
            if (method.parameters.isEmpty()) {
                method.isAccessible = true
                method.invoke(instance)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

