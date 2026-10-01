package tn.eluea.kgpt.provider;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CallerAccessTest {
    @Test public void onlyEnabledImeUidCanAccessConfiguration() {
        try (MockedStatic<Process> process = mockStatic(Process.class)) {
            process.when(Process::myUid).thenReturn(10001);
            Context context = mock(Context.class);
            PackageManager packages = mock(PackageManager.class);
            InputMethodManager manager = mock(InputMethodManager.class);
            InputMethodInfo ime = mock(InputMethodInfo.class);
            when(context.getPackageManager()).thenReturn(packages);
            when(context.getSystemService(Context.INPUT_METHOD_SERVICE)).thenReturn(manager);
            when(manager.getEnabledInputMethodList()).thenReturn(Collections.singletonList(ime));
            when(ime.getPackageName()).thenReturn("enabled.keyboard");
            when(packages.getPackagesForUid(20001)).thenReturn(new String[]{"enabled.keyboard"});
            when(packages.getPackagesForUid(20002)).thenReturn(new String[]{"other.app"});
            assertTrue(CallerAccess.isModuleOrEnabledIme(context, 10001));
            assertTrue(CallerAccess.isModuleOrEnabledIme(context, 20001));
            assertFalse(CallerAccess.isModuleOrEnabledIme(context, 20002));
        }
    }
    @Test public void systemClipboardHookCannotReadCredentialsOrAllConfiguration() {
        try (MockedStatic<Process> process = mockStatic(Process.class)) {
            process.when(Process::myUid).thenReturn(10001);
            Context context = mock(Context.class); PackageManager packages = mock(PackageManager.class);
            when(context.getPackageManager()).thenReturn(packages);
            CallerAccess.enforce(context, Process.SYSTEM_UID, "ai_clipboard_history_v1");
            try { CallerAccess.enforce(context, Process.SYSTEM_UID, "api_key"); fail("Credentials exposed"); }
            catch (SecurityException expected) { }
            try { CallerAccess.enforce(context, Process.SYSTEM_UID, null); fail("All configuration exposed"); }
            catch (SecurityException expected) { }
        }
    }
}
