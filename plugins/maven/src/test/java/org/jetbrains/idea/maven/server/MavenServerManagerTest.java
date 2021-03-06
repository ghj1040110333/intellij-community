/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.maven.server;

import com.google.common.util.concurrent.Uninterruptibles;
import com.intellij.execution.rmi.RemoteProcessSupport;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.ReflectionUtil;
import org.jetbrains.idea.maven.MavenTestCase;
import org.jetbrains.idea.maven.project.MavenWorkspaceSettingsComponent;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class MavenServerManagerTest extends MavenTestCase {

  public void testInitializingDoesntTakeReadAction() throws Exception {
    //make sure all components are initialized to prevent deadlocks
    ensureConnected(MavenServerManager.getInstance().getConnector(myProject, myProjectRoot.getPath()));

    Future result = ApplicationManager.getApplication().runWriteAction(
      (ThrowableComputable<Future, Exception>)() -> ApplicationManager.getApplication().executeOnPooledThread(() -> {
        MavenServerManager.getInstance().shutdown(true);
        ensureConnected(MavenServerManager.getInstance().getConnector(myProject, myProjectRoot.getPath()));
      }));


    long start = System.currentTimeMillis();
    long end = TimeUnit.SECONDS.toMillis(10) + start;
    boolean ok = false;
    while (System.currentTimeMillis() < end && !ok) {
      EdtTestUtil.runInEdtAndWait(() -> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
      });
      try {
        result.get(0, TimeUnit.MILLISECONDS);
        ok = true;
      }
      catch (InterruptedException | java.util.concurrent.ExecutionException e) {
        throw new RuntimeException(e);
      }
      catch (TimeoutException ignore) {
      }
    }
    if (!ok) {
      printThreadDump();
      fail();
    }
    result.cancel(true);
  }

  public void testConnectorRestartAfterVMChanged() {
    MavenWorkspaceSettingsComponent settingsComponent = MavenWorkspaceSettingsComponent.getInstance(myProject);
    String vmOptions = settingsComponent.getSettings().getImportingSettings().getVmOptionsForImporter();
    try {
      MavenServerConnector connector = MavenServerManager.getInstance().getConnector(myProject, myProjectRoot.getPath());
      ensureConnected(connector);
      settingsComponent.getSettings().getImportingSettings().setVmOptionsForImporter(vmOptions + " -DtestVm=test");
      assertNotSame(connector, ensureConnected(MavenServerManager.getInstance().getConnector(myProject, myProjectRoot.getPath())));
    }
    finally {
      settingsComponent.getSettings().getImportingSettings().setVmOptionsForImporter(vmOptions);
    }
  }

  public void testShouldRestartConnectorAutomaticallyIfFailed() {
    MavenServerConnector connector = MavenServerManager.getInstance().getConnector(myProject, myProjectRoot.getPath());
    ensureConnected(connector);
    RemoteProcessSupport support =
      ReflectionUtil.getField(MavenServerConnectorImpl.class, connector, RemoteProcessSupport.class, "mySupport");
    AtomicReference<RemoteProcessSupport.Heartbeat> heartbeat =
      ReflectionUtil.getField(RemoteProcessSupport.class, support, AtomicReference.class, "myHeartbeatRef");
    heartbeat.get().kill(1);
    long now = System.currentTimeMillis();
    while (System.currentTimeMillis() - now < 10_000) {
      if (!connector.checkConnected()) break;
      Uninterruptibles.sleepUninterruptibly(1, TimeUnit.MILLISECONDS);
    }
    assertFalse(connector.checkConnected());
    MavenServerConnector newConnector = MavenServerManager.getInstance().getConnector(myProject, myProjectRoot.getPath());
    ensureConnected(newConnector);
    assertNotSame(connector, newConnector);
  }

  private static MavenServerConnector ensureConnected(MavenServerConnector connector) {
    assertTrue("Connector is Dummy!", connector instanceof MavenServerConnectorImpl);
    long timeout = TimeUnit.SECONDS.toMillis(10);
    long start = System.currentTimeMillis();
    while (connector.getState() == MavenServerConnectorImpl.State.STARTING) {
      if (System.currentTimeMillis() > start + timeout) {
        throw new RuntimeException("Server connector not connected in 10 seconds");
      }
      EdtTestUtil.runInEdtAndWait(() -> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
      });
    }
    assertTrue(connector.checkConnected());
    return connector;
  }
}
