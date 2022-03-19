/*
 * Copyright © 2022 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.support.services;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import io.cdap.cdap.common.NamespaceNotFoundException;
import io.cdap.cdap.common.NotFoundException;
import io.cdap.cdap.common.conf.CConfiguration;
import io.cdap.cdap.common.conf.Constants;
import io.cdap.cdap.common.namespace.RemoteNamespaceQueryClient;
import io.cdap.cdap.common.utils.DirUtils;
import io.cdap.cdap.proto.NamespaceMeta;
import io.cdap.cdap.proto.id.NamespaceId;
import io.cdap.cdap.support.SupportBundleTaskConfiguration;
import io.cdap.cdap.support.job.SupportBundleJob;
import io.cdap.cdap.support.lib.SupportBundleExportRequest;
import io.cdap.cdap.support.lib.SupportBundleFileNames;
import io.cdap.cdap.support.lib.SupportBundleOperationStatus;
import io.cdap.cdap.support.lib.SupportBundlePipelineStatus;
import io.cdap.cdap.support.lib.SupportBundleTaskType;
import io.cdap.cdap.support.status.CollectionState;
import io.cdap.cdap.support.status.SupportBundleConfiguration;
import io.cdap.cdap.support.status.SupportBundleStatus;
import io.cdap.cdap.support.status.SupportBundleTaskStatus;
import io.cdap.cdap.support.task.factory.SupportBundleTaskFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Support bundle service to generate base path, uuid and trigger the job to execute tasks.
 */
public class SupportBundleGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(SupportBundleGenerator.class);
  private static final Gson GSON = new Gson();
  private static final Type MAP_TYPE = new TypeToken<Map<String, Boolean>>() {
  }.getType();
  private final Set<SupportBundleTaskFactory> taskFactories;
  private final CConfiguration cConf;
  private final RemoteNamespaceQueryClient namespaceQueryClient;
  private final String localDir;
  private final List<String> serviceList;
  private final List<String> listOfFileNames;

  @Inject
  SupportBundleGenerator(CConfiguration cConf, RemoteNamespaceQueryClient namespaceQueryClient,
                         @Named(Constants.SupportBundle.TASK_FACTORY) Set<SupportBundleTaskFactory> taskFactories) {
    this.cConf = cConf;
    this.namespaceQueryClient = namespaceQueryClient;
    this.localDir = cConf.get(Constants.SupportBundle.LOCAL_DATA_DIR);
    this.taskFactories = taskFactories;
    this.serviceList = Arrays.asList(Constants.Service.APP_FABRIC_HTTP, Constants.Service.DATASET_EXECUTOR,
                                     Constants.Service.EXPLORE_HTTP_USER_SERVICE, Constants.Service.LOGSAVER,
                                     Constants.Service.MESSAGING_SERVICE, Constants.Service.METADATA_SERVICE,
                                     Constants.Service.METRICS, Constants.Service.METRICS_PROCESSOR,
                                     Constants.Service.RUNTIME, Constants.Service.TRANSACTION, "pipeline");
    this.listOfFileNames = new ArrayList<>(serviceList);
    listOfFileNames.addAll(Arrays.asList("applicationFile", "runtimelog", "runtimeinfo"));
  }

  /**
   * Generates support bundle
   */
  public String generate(SupportBundleConfiguration config, ExecutorService executorService) throws Exception {
    NamespaceId namespace = Optional.ofNullable(config.getNamespace()).map(NamespaceId::new).orElse(null);
    List<NamespaceId> namespaces = new ArrayList<>();

    if (namespace == null) {
      namespaces.addAll(
        namespaceQueryClient.list().stream().map(NamespaceMeta::getNamespaceId).collect(Collectors.toList()));
    } else {
      namespaces.add(validNamespace(namespace));
    }
    // Puts all the files under the uuid path
    File baseDirectory = new File(localDir);
    DirUtils.mkdirs(baseDirectory);
    String uuid = UUID.randomUUID().toString();
    File uuidPath = new File(localDir, uuid);

    deleteOldFoldersIfExceedLimit(baseDirectory);
    DirUtils.mkdirs(uuidPath);

    SupportBundleStatus supportBundleStatus = SupportBundleStatus.builder()
      .setBundleId(uuid)
      .setStartTimestamp(System.currentTimeMillis())
      .setStatus(CollectionState.IN_PROGRESS)
      .setParameters(config)
      .build();
    addToStatus(supportBundleStatus, uuidPath.getPath());

    SupportBundleJob supportBundleJob =
      new SupportBundleJob(taskFactories, executorService, cConf, supportBundleStatus);
    SupportBundleTaskConfiguration supportBundleTaskConfiguration =
      new SupportBundleTaskConfiguration(config, uuid, uuidPath, namespaces, supportBundleJob);

    try {
      executorService.execute(() -> supportBundleJob.generateBundle(supportBundleTaskConfiguration));
    } catch (Exception e) {
      LOG.error("Failed to finish execute tasks", e);
      SupportBundleStatus failedBundleStatus = SupportBundleStatus.builder(supportBundleStatus)
        .setStatus(CollectionState.FAILED)
        .setFinishTimestamp(System.currentTimeMillis())
        .setStatusDetails(e.getMessage())
        .build();
      addToStatus(failedBundleStatus, uuidPath.getPath());
    }

    return uuid;
  }

  /** Ensure previous executor has finished all the jobs and tasks before starting a new one */
  public String ensurePreviousExecutorFinish() throws IOException {
    File baseDirectory = new File(localDir);
    int fileCount = DirUtils.listFiles(baseDirectory)
      .stream().filter(file -> !file.isHidden() && file.isDirectory()).collect(Collectors.toList()).size();
    if (fileCount == 0) {
      return null;
    }
    File latestDirectory = getLatestFolder(baseDirectory);
    SupportBundleStatus supportBundleStatus = getBundleStatus(latestDirectory);
    if (supportBundleStatus != null && supportBundleStatus.getStatus() == CollectionState.IN_PROGRESS) {
      return supportBundleStatus.getBundleId();
    }
    return null;
  }

  /**
   * Gets single support bundle status with uuid
   */
  @VisibleForTesting
  SupportBundleStatus getBundleStatus(File uuidFile) throws IllegalArgumentException, IOException {
    File statusFile = new File(uuidFile, SupportBundleFileNames.STATUS_FILE_NAME);
    if (!statusFile.exists()) {
      throw new IllegalArgumentException("Failed to find this status file");
    }
    return readStatusJson(statusFile);
  }

  /**
   * Deletes old folders after certain number of folders exist
   */
  @VisibleForTesting
  public void deleteOldFoldersIfExceedLimit(File baseDirectory) throws IOException {
    int fileCount = DirUtils.list(baseDirectory).size();
    // We want to keep consistent number of bundle to provide to customer
    int folderMaxNumber = cConf.getInt(Constants.SupportBundle.MAX_FOLDER_SIZE);
    if (fileCount >= folderMaxNumber) {
      File oldFilesDirectory = getOldestFolder(baseDirectory);
      DirUtils.deleteDirectoryContents(oldFilesDirectory);
    }
  }

  /**
   * Deletes select folder
   */
  public void deleteBundle(File baseDirectory) throws IOException {
    DirUtils.deleteDirectoryContents(baseDirectory);
  }

  /**
   * Get single support bundle overall status
   */
  public SupportBundleOperationStatus getBundle(String uuid) throws IOException {
    File uuidFile = getUUIDFile(uuid);
    if (!uuidFile.exists()) {
      LOG.debug(String.format("No such uuid '%s' in Support Bundle.", uuid));
      return null;
    }

    File statusFile = new File(uuidFile, "status.json");
    SupportBundleOperationStatus supportBundleOperationStatus = null;
    if (statusFile.exists()) {
      SupportBundlePipelineStatus supportBundlePipelineStatus;
      SupportBundleStatus supportBundleStatus = getBundleStatus(uuidFile);
      Set<SupportBundleTaskStatus> supportBundleTaskStatusSet = supportBundleStatus.getTasks();
      supportBundlePipelineStatus = collectSupportBundleTaskStatus(supportBundleTaskStatusSet);
      supportBundleOperationStatus =
        new SupportBundleOperationStatus(uuidFile.getName(), supportBundleStatus.getStatus(),
                                         supportBundlePipelineStatus);
    }
    return supportBundleOperationStatus;
  }

  public String createBundleZip(String uuid, Path tmpPath, SupportBundleExportRequest bundleExportRequest)
    throws Exception {
    List<String> requestFiles = bundleExportRequest.getSupportBundleRequestFileList().getFiles();

    File uuidFile = getUUIDFile(uuid);
    MessageDigest digest = null;
    if (!uuidFile.exists()) {
      throw new NotFoundException(String.format("This bundle id %s is not existed", uuid));
    }
    digest = MessageDigest.getInstance("SHA-256");
    try (ZipOutputStream zipOut = new ZipOutputStream(
      new DigestOutputStream(Files.newOutputStream(tmpPath, StandardOpenOption.TRUNCATE_EXISTING), digest))) {
      for (String filePath : requestFiles) {
        File requestFile = new File(uuidFile, filePath);
        if (requestFile.exists()) {
          ZipEntry entry = new ZipEntry(uuidFile.getName() + "/" + filePath);
          zipOut.putNextEntry(entry);
          Files.copy(requestFile.toPath(), zipOut);
          zipOut.closeEntry();
        }
      }
    }
    return String.format("%s=%s", digest.getAlgorithm().toLowerCase(),
                         Base64.getEncoder().encodeToString(digest.digest()));
  }

  public File getUUIDFile(String uuid) {
    File baseDirectory = new File(cConf.get(Constants.SupportBundle.LOCAL_DATA_DIR));
    return new File(baseDirectory, uuid);
  }

  /**
   * Gets oldest folder from the root directory
   */
  private File getOldestFolder(File baseDirectory) {
    List<File> uuidFiles = DirUtils.listFiles(baseDirectory)
      .stream()
      .filter(file -> !file.isHidden() && file.isDirectory())
      .collect(Collectors.toList());
    return Collections.min(uuidFiles, Comparator.<File, Boolean>comparing(f1 -> {
      try {
        return getBundleStatus(f1).getStatus() != CollectionState.FAILED;
      } catch (Exception e) {
        throw new RuntimeException("Failed to get file status ", e);
      }
    }).thenComparing(File::lastModified));
  }

  /**
   * Gets latest folder from the root directory
   */
  private File getLatestFolder(File baseDirectory) {
    List<File> uuidFiles = DirUtils.listFiles(baseDirectory).stream()
      .filter(file -> !file.getName().startsWith(".") && !file.isHidden() && file.isDirectory())
      .collect(Collectors.toList());
    return Collections.max(uuidFiles, Comparator.comparing(File::lastModified));
  }

  /**
   * Update status file
   */
  private void addToStatus(SupportBundleStatus supportBundleStatus, String basePath) throws IOException {
    try (FileWriter statusFile = new FileWriter(new File(basePath, SupportBundleFileNames.STATUS_FILE_NAME))) {
      GSON.toJson(supportBundleStatus, statusFile);
    }
  }

  private SupportBundleStatus readStatusJson(File statusFile) throws IOException {
    SupportBundleStatus supportBundleStatus;
    try (Reader reader = Files.newBufferedReader(statusFile.toPath(), StandardCharsets.UTF_8)) {
      supportBundleStatus = GSON.fromJson(reader, SupportBundleStatus.class);
    }
    return supportBundleStatus;
  }

  private NamespaceId validNamespace(NamespaceId namespace) throws Exception {
    if (!namespaceQueryClient.exists(namespace)) {
      throw new NamespaceNotFoundException(namespace);
    }

    return namespace;
  }

  private SupportBundlePipelineStatus collectSupportBundleTaskStatus(
    Set<SupportBundleTaskStatus> supportBundleTaskStatusSet) {
    CollectionState systemLogTaskStatus = CollectionState.INVALID;
    CollectionState pipelineInfoTaskStatus = CollectionState.INVALID;
    CollectionState runtimeInfoTaskStatus = CollectionState.INVALID;
    CollectionState runtimeLogTaskStatus = CollectionState.INVALID;
    CollectionState vmInfoTaskStatus = CollectionState.INVALID;
    
    for (SupportBundleTaskStatus supportBundleTaskStatus : supportBundleTaskStatusSet) {
      SupportBundleTaskType currentTaskType = SupportBundleTaskType.valueOf(supportBundleTaskStatus.getType());
      switch (currentTaskType) {
        case SupportBundleSystemLogTask:
          systemLogTaskStatus = supportBundleTaskStatus.getStatus();
          break;
        case SupportBundlePipelineInfoTask:
          pipelineInfoTaskStatus = supportBundleTaskStatus.getStatus();
          break;
        case SupportBundleVMInfoTask:
          vmInfoTaskStatus = supportBundleTaskStatus.getStatus();
          break;
      }
      if (supportBundleTaskStatus.getSubTasks().size() > 0) {
        for (SupportBundleTaskStatus subTaskSupportBundleTaskStatus : supportBundleTaskStatus.getSubTasks()) {
          SupportBundleTaskType currentSubTaskType =
            SupportBundleTaskType.valueOf(subTaskSupportBundleTaskStatus.getType());
          switch (currentSubTaskType) {
            case SupportBundleRuntimeInfoTask:
              runtimeInfoTaskStatus = subTaskSupportBundleTaskStatus.getStatus();
              break;
            case SupportBundlePipelineRunLogTask:
              runtimeLogTaskStatus = subTaskSupportBundleTaskStatus.getStatus();
              break;
          }
        }
      }
    }
    SupportBundlePipelineStatus supportBundlePipelineStatus =
      new SupportBundlePipelineStatus(systemLogTaskStatus, pipelineInfoTaskStatus, runtimeInfoTaskStatus,
                                      runtimeLogTaskStatus, vmInfoTaskStatus);
    return supportBundlePipelineStatus;
  }
}
