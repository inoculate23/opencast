/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.execute.operation.handler;

import org.opencastproject.execute.api.ExecuteException;
import org.opencastproject.execute.api.ExecuteService;
import org.opencastproject.inspection.api.MediaInspectionException;
import org.opencastproject.inspection.api.MediaInspectionService;
import org.opencastproject.job.api.Job;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.MediaPackageElementParser;
import org.opencastproject.mediapackage.MediaPackageException;
import org.opencastproject.mediapackage.Track;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.ConfiguredTagsAndFlavors;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;
import org.opencastproject.workflow.api.WorkflowOperationResultImpl;
import org.opencastproject.workspace.api.Workspace;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

/**
 * Runs an operation multiple times with each MediaPackageElement matching the characteristics
 */
@Component(
    immediate = true,
    service = WorkflowOperationHandler.class,
    property = {
        "service.description=Execute Many Workflow Operation Handler",
        "workflow.operation=execute-many"
    }
)
public class ExecuteManyWorkflowOperationHandler extends AbstractWorkflowOperationHandler {

  /** The logging facility */
  private static final Logger logger = LoggerFactory.getLogger(ExecuteManyWorkflowOperationHandler.class);

  /** Property containing the command to run */
  public static final String EXEC_PROPERTY = "exec";

  /** Property containing the list of command parameters */
  public static final String PARAMS_PROPERTY = "params";

  /** Property containingn an approximation of the load imposed by running this operation */
  public static final String LOAD_PROPERTY = "load";

  /** Property containing the "flavor" that a mediapackage elements must have in order to be used as input arguments */
  public static final String SOURCE_FLAVOR_PROPERTY = "source-flavor";

  /** Property containing the filename of the elements created by this operation */
  public static final String OUTPUT_FILENAME_PROPERTY = "output-filename";

  /** Property containing the expected type of the element generated by this operation */
  public static final String EXPECTED_TYPE_PROPERTY = "expected-type";

  /**
   * Property containing the tags that must exist on a mediapackage element for the element to be used as an input
   * arguments
   */
  public static final String SOURCE_TAGS_PROPERTY = "source-tags";

  /** Property defining whether the track is required to contain or not contain an audio stream to be used an input */
  public static final String SOURCE_AUDIO_PROPERTY = "source-audio";

  /** Property defining whether the track is required to contain or not contain a video stream to be used an input */
  public static final String SOURCE_VIDEO_PROPERTY = "source-video";

  /** Property defining whether the track is required to contain or not contain a subtitle stream to be used an input */
  public static final String SOURCE_SUBTITLE_PROPERTY = "source-subtitle";

  /** Property containing the flavor that the resulting mediapackage elements will be assigned */
  public static final String TARGET_FLAVOR_PROPERTY = "target-flavor";

  /** Property containing the tags that the resulting mediapackage elements will be assigned */
  public static final String TARGET_TAGS_PROPERTY = "target-tags";

  /** Property to control whether command output will be used to set workflow properties */
  public static final String SET_WF_PROPS_PROPERTY = "set-workflow-properties";

  /** The text analyzer */
  protected ExecuteService executeService;

  /** Reference to the media inspection service */
  private MediaInspectionService inspectionService = null;

  /** The workspace service */
  protected Workspace workspace;

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler#start(org.opencastproject.workflow.api.WorkflowInstance,
   *      JobContext)
   */
  @Override
  public WorkflowOperationResult start(WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {

    MediaPackage mediaPackage = workflowInstance.getMediaPackage();
    WorkflowOperationInstance operation = workflowInstance.getCurrentOperation();

    logger.debug("Running execute workflow operation with ID {}", operation.getId());

    // Get operation parameters
    String exec = StringUtils.trimToNull(operation.getConfiguration(EXEC_PROPERTY));
    String params = StringUtils.trimToNull(operation.getConfiguration(PARAMS_PROPERTY));
    float load = 1.0f;
    String loadPropertyStr = StringUtils.trimToEmpty(operation.getConfiguration(LOAD_PROPERTY));
    if (StringUtils.isNotBlank(loadPropertyStr)) {
      try {
        load = Float.parseFloat(loadPropertyStr);
      } catch (NumberFormatException e) {
        String description = StringUtils.trimToEmpty(operation.getDescription());
        logger.warn("Ignoring invalid load value '{}' on execute operation with description '{}'", loadPropertyStr,
                description);
      }
    }
    ConfiguredTagsAndFlavors tagsAndFlavors = getTagsAndFlavors(workflowInstance,
        Configuration.many, Configuration.many, Configuration.many, Configuration.many);
    List<MediaPackageElementFlavor> sourceFlavor = tagsAndFlavors.getSrcFlavors();
    List<String> sourceTagList = tagsAndFlavors.getSrcTags();
    String sourceAudio = StringUtils.trimToNull(operation.getConfiguration(SOURCE_AUDIO_PROPERTY));
    String sourceVideo = StringUtils.trimToNull(operation.getConfiguration(SOURCE_VIDEO_PROPERTY));
    String sourceSubtitle = StringUtils.trimToNull(operation.getConfiguration(SOURCE_SUBTITLE_PROPERTY));
    List<MediaPackageElementFlavor> targetFlavorList = tagsAndFlavors.getTargetFlavors();
    List<String> targetTags = tagsAndFlavors.getTargetTags();
    String outputFilename = StringUtils.trimToNull(operation.getConfiguration(OUTPUT_FILENAME_PROPERTY));
    String expectedTypeStr = StringUtils.trimToNull(operation.getConfiguration(EXPECTED_TYPE_PROPERTY));

    boolean setWfProps = Boolean.valueOf(StringUtils.trimToNull(operation.getConfiguration(SET_WF_PROPS_PROPERTY)));

    MediaPackageElementFlavor matchingFlavor = null;
    if (!sourceFlavor.isEmpty())
      matchingFlavor = sourceFlavor.get(0);

    // Unmarshall target flavor
    MediaPackageElementFlavor targetFlavor = null;
    if (!targetFlavorList.isEmpty())
      targetFlavor = targetFlavorList.get(0);

    // Unmarshall expected mediapackage element type
    MediaPackageElement.Type expectedType = null;
    if (expectedTypeStr != null) {
      for (MediaPackageElement.Type type : MediaPackageElement.Type.values())
        if (type.toString().equalsIgnoreCase(expectedTypeStr)) {
          expectedType = type;
          break;
        }

      if (expectedType == null)
        throw new WorkflowOperationException("'" + expectedTypeStr + "' is not a valid element type");
    }

    // Select the tracks based on source flavors and tags
    Set<MediaPackageElement> inputSet = new HashSet<>();
    for (MediaPackageElement element : mediaPackage.getElementsByTags(sourceTagList)) {
      MediaPackageElementFlavor elementFlavor = element.getFlavor();
      if (sourceFlavor == null || (elementFlavor != null && elementFlavor.matches(matchingFlavor))) {

        // Check for audio or video streams in the track, if specified
        if ((element instanceof Track) && (sourceAudio != null)
            && (Boolean.parseBoolean(sourceAudio) != ((Track) element).hasAudio())) {
          continue;
        }

        if ((element instanceof Track) && (sourceVideo != null)
            && (Boolean.parseBoolean(sourceVideo) != ((Track) element).hasVideo())) {
          continue;
        }

        if ((element instanceof Track) && (sourceSubtitle != null)
            && (Boolean.parseBoolean(sourceSubtitle) != ((Track) element).hasSubtitle())) {
          continue;
        }

        inputSet.add(element);
      }
    }

    if (inputSet.size() == 0) {
      logger.warn("Mediapackage {} has no suitable elements to execute the command {} based on tags {}, flavor {}, sourceAudio {}, sourceVideo {}, sourceSubtitle {}",
              mediaPackage, exec, sourceTagList, sourceFlavor, sourceAudio, sourceVideo, sourceSubtitle);
      return createResult(mediaPackage, Action.CONTINUE);
    }

    MediaPackageElement[] inputElements = inputSet.toArray(new MediaPackageElement[inputSet.size()]);

    Map<String, String> wfProps = new HashMap<>();

    try {
      Job[] jobs = new Job[inputElements.length];
      MediaPackageElement[] resultElements = new MediaPackageElement[inputElements.length];
      long totalTimeInQueue = 0;

      for (int i = 0; i < inputElements.length; i++)
        jobs[i] = executeService.execute(exec, params, inputElements[i], outputFilename, expectedType, load);

      // Wait for all jobs to be finished
      if (!waitForStatus(jobs).isSuccess())
        throw new WorkflowOperationException("Execute operation failed");

      // Find which output elements are tracks and inspect them
      HashMap<Integer, Job> jobMap = new HashMap<>();
      for (int i = 0; i < jobs.length; i++) {
        // Add this job's queue time to the total
        totalTimeInQueue += jobs[i].getQueueTime();
        if (StringUtils.trimToNull(jobs[i].getPayload()) != null) {
          resultElements[i] = MediaPackageElementParser.getFromXml(jobs[i].getPayload());
          if (resultElements[i].getElementType() == MediaPackageElement.Type.Track) {
            jobMap.put(i, inspectionService.inspect(resultElements[i].getURI()));
          }
        } else
          resultElements[i] = inputElements[i];
      }

      if (jobMap.size() > 0) {
        if (!waitForStatus(jobMap.values().toArray(new Job[jobMap.size()])).isSuccess())
          throw new WorkflowOperationException("Execute operation failed in track inspection");

        for (Entry<Integer, Job> entry : jobMap.entrySet()) {
          // Add this job's queue time to the total
          totalTimeInQueue += entry.getValue().getQueueTime();
          resultElements[entry.getKey()] = MediaPackageElementParser.getFromXml(entry.getValue().getPayload());
        }
      }

      for (int i = 0; i < resultElements.length; i++) {
        if (resultElements[i] != inputElements[i]) {

          if (setWfProps) {
            // The job payload is a file with set of properties for the workflow
            final Properties properties = new Properties();
            File propertiesFile = workspace.get(resultElements[i].getURI());
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(propertiesFile), StandardCharsets.UTF_8)) {
              properties.load(reader);
            }
            logger.debug("Loaded {} properties from {}", properties.size(), propertiesFile);
            workspace.deleteFromCollection(ExecuteService.COLLECTION, propertiesFile.getName());

            // Add the properties to the wfProps
            wfProps.putAll((Map) properties);

          } else {
            // The job payload is a new element for the MediaPackage
            // Store new element to mediaPackage
            mediaPackage.addDerived(resultElements[i], inputElements[i]);
            // Store new element to mediaPackage
            URI uri = workspace.moveTo(resultElements[i].getURI(), mediaPackage.getIdentifier().toString(),
                  resultElements[i].getIdentifier(), outputFilename);

            resultElements[i].setURI(uri);

            // Set new flavor
            if (targetFlavor != null) {
              String targetFlavorType = targetFlavor.getType();
              String targetFlavorSubtype = targetFlavor.getSubtype();

              if (MediaPackageElementFlavor.WILDCARD.equals(targetFlavorType)) {
                targetFlavorType = inputElements[i].getFlavor().getType();
              }

              if (MediaPackageElementFlavor.WILDCARD.equals(targetFlavorSubtype)) {
                targetFlavorSubtype = inputElements[i].getFlavor().getSubtype();
              }

              String resolvedTargetFlavorStr =
                  targetFlavorType + MediaPackageElementFlavor.SEPARATOR + targetFlavorSubtype;
              resultElements[i].setFlavor(MediaPackageElementFlavor.parseFlavor(resolvedTargetFlavorStr));
            }
          }
        }

        // Set new tags
        if (targetTags != null) {
          // Assume the tags starting with "-" means we want to eliminate such tags form the result element
          for (String tag : targetTags) {
            if (tag.startsWith("-"))
              // We remove the tag resulting from stripping all the '-' characters at the beginning of the tag
              resultElements[i].removeTag(tag.replaceAll("^-+", ""));
            else
              resultElements[i].addTag(tag);
          }
        }
      }

      WorkflowOperationResult result = createResult(mediaPackage, wfProps, Action.CONTINUE, totalTimeInQueue);
      logger.debug("Execute operation {} completed", operation.getId());

      return result;

    } catch (ExecuteException e) {
      throw new WorkflowOperationException(e);
    } catch (MediaPackageException e) {
      throw new WorkflowOperationException("Some result element couldn't be serialized", e);
    } catch (NotFoundException e) {
      throw new WorkflowOperationException("Could not find mediapackage", e);
    } catch (IOException e) {
      throw new WorkflowOperationException("Error unmarshalling a result mediapackage element", e);
    } catch (MediaInspectionException e) {
      throw new WorkflowOperationException("Error inspecting one of the created tracks", e);
    }

  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler#skip(org.opencastproject.workflow.api.WorkflowInstance,
   *      JobContext)
   */
  @Override
  public WorkflowOperationResult skip(WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {
    return new WorkflowOperationResultImpl(workflowInstance.getMediaPackage(), null, Action.SKIP, 0);
  }

  @Override
  public String getId() {
    return "execute";
  }

  @Override
  public String getDescription() {
    return "Executes command line workflow operations in workers";
  }

  @Override
  public void destroy(WorkflowInstance workflowInstance, JobContext context) throws WorkflowOperationException {
    // Do nothing (nothing to clean up, the command line program should do this itself)
  }

  /**
   * Sets the service
   *
   * @param service
   */
  @Reference
  public void setExecuteService(ExecuteService service) {
    executeService = service;
  }

  /**
   * Sets a reference to the workspace service.
   *
   * @param workspace
   */
  @Reference
  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  /**
   * Sets the media inspection service
   *
   * @param mediaInspectionService
   *          an instance of the media inspection service
   */
  @Reference
  protected void setMediaInspectionService(MediaInspectionService mediaInspectionService) {
    inspectionService = mediaInspectionService;
  }

  @Reference
  @Override
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    super.setServiceRegistry(serviceRegistry);
  }

}
