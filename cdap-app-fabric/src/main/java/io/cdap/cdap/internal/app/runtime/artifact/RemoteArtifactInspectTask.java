package io.cdap.cdap.internal.app.runtime.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.multibindings.MapBinder;
import io.cdap.cdap.api.artifact.CloseableClassLoader;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.service.worker.RunnableTask;
import io.cdap.cdap.api.service.worker.RunnableTaskContext;
import io.cdap.cdap.app.guice.DefaultProgramRunnerFactory;
import io.cdap.cdap.app.runtime.ProgramRunner;
import io.cdap.cdap.app.runtime.ProgramRunnerFactory;
import io.cdap.cdap.app.runtime.ProgramRuntimeProvider;
import io.cdap.cdap.app.runtime.ProgramStateWriter;
import io.cdap.cdap.common.conf.CConfiguration;
import io.cdap.cdap.common.guice.DFSLocationModule;
import io.cdap.cdap.common.guice.SupplierProviderBridge;
import io.cdap.cdap.common.id.Id;
import io.cdap.cdap.common.lang.jar.BundleJarUtil;
import io.cdap.cdap.internal.app.program.MessagingProgramStateWriter;
import io.cdap.cdap.internal.app.worker.sidecar.ArtifactLocalizerClient;
import io.cdap.cdap.internal.io.SchemaTypeAdapter;
import io.cdap.cdap.master.environment.MasterEnvironments;
import io.cdap.cdap.master.spi.environment.MasterEnvironment;
import io.cdap.cdap.messaging.guice.MessagingClientModule;
import io.cdap.cdap.proto.ProgramType;
import io.cdap.cdap.proto.id.NamespaceId;
import io.cdap.cdap.security.auth.context.AuthenticationContextModules;
import io.cdap.cdap.security.impersonation.DefaultImpersonator;
import io.cdap.cdap.security.spi.authentication.AuthenticationContext;
import org.apache.twill.discovery.DiscoveryService;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link RemoteArtifactInspectTask} is a {@link RunnableTask} for performing artifact inspection remotely.
 */
public class RemoteArtifactInspectTask implements RunnableTask {
  private static final Logger LOG = LoggerFactory.getLogger(RemoteArtifactInspectTask.class);

  private static final Gson GSON =
    new GsonBuilder().registerTypeAdapter(Schema.class, new SchemaTypeAdapter()).create();

  private final CConfiguration cConf;

  @Inject
  public RemoteArtifactInspectTask(CConfiguration cConf) {
    this.cConf = cConf;
  }

  @Override
  public void run(RunnableTaskContext context) throws Exception {
    LOG.warn("wyzhang: RemoteArtifactInspectTask: run start");

    Injector injector = createInjector();

    ProgramRunnerFactory programRunnerFactory = injector.getInstance(ProgramRunnerFactory.class);
    ArtifactClassLoaderFactory factory = new ArtifactClassLoaderFactory(cConf, programRunnerFactory);
    DefaultArtifactInspector inspector = new DefaultArtifactInspector(cConf, factory,
                                                                      new DefaultImpersonator(cConf, null));

    Id.Artifact artifactId = new Id.Artifact(Id.Namespace.from(context.getNamespace()),
                                             context.getArtifactId().getName(),
                                             context.getArtifactId().getVersion());
    RemoteArtifactInspectTaskRequest req = GSON.fromJson(context.getParam(), RemoteArtifactInspectTaskRequest.class);

    LOG.warn("wyzhang: RemoteArtifactInspectTask: req {}", req);

    // Localize and unpacked parent artifacts
    LOG.warn("wyzhang: RemoteArtifactInspectTask: Localize and unpacked parent artifacts");
    List<File> unpackedParents = new ArrayList<>();
    ArtifactLocalizerClient artifactLocalizerClient = injector.getInstance(ArtifactLocalizerClient.class);
    for (ArtifactDescriptor parentArtifact : req.getParentArtifacts()) {
      File unpacked = artifactLocalizerClient.getUnpackedArtifactLocation(
        Artifacts.toProtoArtifactId(new NamespaceId(parentArtifact.getNamespace()), parentArtifact.getArtifactId()));
      unpackedParents.add(unpacked);
    }

    // Create parent classloader
    LOG.warn("wyzhang: RemoteArtifactInspectTask: Create parent classloader");
    CloseableClassLoader parentClassLoader = null;
    if (!unpackedParents.isEmpty()) {
      parentClassLoader = factory.createClassLoader(unpackedParents.iterator());
    }

    // Localize and unpacked the artifact to inspect
    File artifactFile = download(req.getArtifactURI(), injector.getInstance(AuthenticationContext.class));
    LOG.warn("wyzhang: RemoteArtifactInspectTask: downloaded from {} to {}",
             req.getArtifactURI(), artifactFile.getAbsolutePath());
    File unpackedDir = Files.createTempDirectory("artifact-").toFile();
    BundleJarUtil.prepareClassLoaderFolder(artifactFile, unpackedDir);

    // Inspect
    ArtifactClassesWithMetadata metadata = null;
    File tmpDir = Files.createTempDirectory("artifact-tmp-").toFile();
    LOG.warn("wyzhang: RemoteArtifactInspectTask: start inspect: artifactId = {}", artifactId);
    LOG.warn("wyzhang: RemoteArtifactInspectTask: start inspect: artifactFile = {}", artifactId);
    LOG.warn("wyzhang: RemoteArtifactInspectTask: start inspect: unpackedDir = {}", artifactId);
    LOG.warn("wyzhang: RemoteArtifactInspectTask: start inspect: parentClassLoader = {}", artifactId);
    LOG.warn("wyzhang: RemoteArtifactInspectTask: start inspect: additional plugins = {}", artifactId);
    LOG.warn("wyzhang: RemoteArtifactInspectTask: start inspect: tmpDir = {}", tmpDir);
    metadata = inspector.inspectArtifact(artifactId,
                                         artifactFile,
                                         unpackedDir,
                                         parentClassLoader,
                                         req.getAdditionalPlugins(),
                                         tmpDir);

    context.writeResult(GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));

    LOG.warn("wyzhang: RemoteArtifactInspectTask: run end");
  }

  private File download(URI uri, AuthenticationContext authenticationContext) throws IOException {
    LOG.warn("wyzhang: RemoteArtifactInspectTask download uri {}", uri);

    MasterEnvironment masterEnv = MasterEnvironments.getMasterEnvironment();

    File tmpFile = File.createTempFile("tmp-", ".jar");
    FileFetcher fileFetcher = new FileFetcher(cConf,
                                              authenticationContext,
                                              masterEnv.getDiscoveryServiceClientSupplier().get());
    try (OutputStream os = new FileOutputStream(tmpFile)) {
      fileFetcher.download(uri, os);
    }
    return tmpFile;
  }

  private Injector createInjector() {
    MasterEnvironment masterEnv = MasterEnvironments.getMasterEnvironment();
    List<Module> modules = new ArrayList<>();

    modules.add(new AbstractModule() {
      @Override
      protected void configure() {
        bind(DiscoveryService.class)
          .toProvider(new SupplierProviderBridge<>(masterEnv.getDiscoveryServiceSupplier()));
        bind(DiscoveryServiceClient.class)
          .toProvider(new SupplierProviderBridge<>(masterEnv.getDiscoveryServiceClientSupplier()));
      }
    });
    modules.add(new DFSLocationModule());
    modules.add(new MessagingClientModule());
    modules.add(new AuthenticationContextModules().getMasterModule());
    modules.add(new AbstractModule() {
      @Override
      protected void configure() {
        bind(CConfiguration.class).toInstance(cConf);
        // Bind ProgramRunner
        MapBinder<ProgramType, ProgramRunner> runnerFactoryBinder =
          MapBinder.newMapBinder(binder(), ProgramType.class, ProgramRunner.class);
        bind(ProgramStateWriter.class).to(MessagingProgramStateWriter.class).in(Scopes.SINGLETON);
        bind(ProgramRuntimeProvider.Mode.class).toInstance(ProgramRuntimeProvider.Mode.LOCAL);
        bind(ProgramRunnerFactory.class).to(DefaultProgramRunnerFactory.class).in(Scopes.SINGLETON);
      }
    });
    return Guice.createInjector(modules);
  }
}
