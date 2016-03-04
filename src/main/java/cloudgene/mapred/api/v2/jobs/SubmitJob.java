package cloudgene.mapred.api.v2.jobs;

import genepi.hadoop.HdfsUtil;
import genepi.io.FileUtil;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.util.Streams;
import org.apache.commons.io.FileUtils;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;

import cloudgene.mapred.core.User;
import cloudgene.mapred.jobs.CloudgeneJob;
import cloudgene.mapred.jobs.WorkflowEngine;
import cloudgene.mapred.util.BaseResource;
import cloudgene.mapred.util.HashUtil;
import cloudgene.mapred.util.PublicUser;
import cloudgene.mapred.util.Settings;
import cloudgene.mapred.wdl.WdlApp;
import cloudgene.mapred.wdl.WdlParameter;
import cloudgene.mapred.wdl.WdlReader;

public class SubmitJob extends BaseResource {

	@Post
	public Representation post(Representation entity) {

		User user = getAuthUser();
		String tool = getAttribute("tool");

		String filename = getSettings().getApp(user, tool);
		WdlApp app = null;
		try {
			app = WdlReader.loadAppFromFile(filename);
		} catch (Exception e1) {

			return error404("Application '"
					+ tool
					+ "' not found or the request requires user authentication.");

		}

		if (app.getMapred() == null) {
			return error404("Application '" + tool + "' has no mapred section.");
		}

		WorkflowEngine engine = getWorkflowEngine();
		Settings settings = getSettings();

		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
		String id = "job-" + sdf.format(new Date());

		if (user != null) {
			// private mode

			int maxPerUser = settings.getMaxRunningJobsPerUser();
			if (engine.getJobsByUser(user).size() >= maxPerUser) {
				return error400("Only " + maxPerUser
						+ " jobs per user can be executed simultaneously.");
			}

		} else {

			// public mode
			user = PublicUser.getUser(getDatabase());
			id = id + "-" + HashUtil.getMD5(id + "-lukas");

		}

		int maxJobs = settings.getMaxRunningJobs();
		if (engine.getActiveCount() >= maxJobs) {
			return error400("More than " + maxJobs
					+ "  jobs are currently in the queue.");
		}

		String hdfsWorkspace = HdfsUtil.path(getSettings().getHdfsWorkspace(),
				id);
		String localWorkspace = FileUtil.path(
				getSettings().getLocalWorkspace(), id);
		FileUtil.createDirectory(localWorkspace);

		Map<String, String> inputParams = parseAndUpdateInputParams(entity,
				app, hdfsWorkspace, localWorkspace);

		if (inputParams == null) {
			return error400("Error during input parameter parsing.");
		}

		CloudgeneJob job = new CloudgeneJob(user, id, app.getMapred(),
				inputParams);
		job.setId(id);
		job.setName(id);
		job.setLocalWorkspace(localWorkspace);
		job.setHdfsWorkspace(hdfsWorkspace);
		job.setSettings(getSettings());
		job.setRemoveHdfsWorkspace(getSettings().isRemoveHdfsWorkspace());
		job.setApplication(app.getName() + " " + app.getVersion());
		job.setApplicationId(tool);

		engine.submit(job);

		Map<String, Object> params = new HashMap<String, Object>();
		params.put("id", id);
		return ok("Your job was successfully added to the job queue.", params);

	}

	private Map<String, String> parseAndUpdateInputParams(
			Representation entity, WdlApp app, String hdfsWorkspace,
			String localWorkspace) {
		Map<String, String> props = new HashMap<String, String>();

		try {
			FileItemIterator iterator = parseRequest(entity);

			// uploaded files
			while (iterator.hasNext()) {

				FileItemStream item = iterator.next();

				String name = item.getName();

				if (name != null) {

					// file parameter
					// write local file
					String tmpFile = getSettings().getTempFilename(
							item.getName());
					File file = new File(tmpFile);

					FileUtils.copyInputStreamToFile(item.openStream(), file);

					// import into hdfs
					String entryName = item.getName();

					// remove upload indentification!
					String fieldName = item.getFieldName()
							.replace("-upload", "").replace("input-", "");

					boolean hdfs = false;
					boolean folder = false;

					for (WdlParameter input : app.getMapred().getInputs()) {
						if (input.getId().equals(fieldName)) {
							hdfs = (input.getType().equals(
									WdlParameter.HDFS_FOLDER) || input
									.getType().equals(WdlParameter.HDFS_FILE));
							folder = (input.getType()
									.equals(WdlParameter.HDFS_FOLDER))
									|| (input.getType()
											.equals(WdlParameter.LOCAL_FOLDER));
						}
					}

					if (hdfs) {

						String targetPath = HdfsUtil.path(hdfsWorkspace,
								fieldName);

						String target = HdfsUtil.path(targetPath, entryName);

						HdfsUtil.put(tmpFile, target);

						// deletes temporary file
						FileUtil.deleteFile(tmpFile);

						if (folder) {
							// folder
							props.put(fieldName, HdfsUtil.makeAbsolute(HdfsUtil
									.path(hdfsWorkspace, fieldName)));
						} else {
							// file
							props.put(fieldName, HdfsUtil.makeAbsolute(HdfsUtil
									.path(hdfsWorkspace, fieldName, entryName)));
						}

					} else {

						// copy to workspace in temp directory

						String targetPath = FileUtil.path(localWorkspace,
								"temp", fieldName);

						FileUtil.createDirectory(targetPath);

						String target = FileUtil.path(targetPath, entryName);

						FileUtil.copy(tmpFile, target);

						// deletes temporary file
						FileUtil.deleteFile(tmpFile);

						if (folder) {
							// folder
							props.put(fieldName,
									new File(targetPath).getAbsolutePath());
						} else {
							// file
							props.put(fieldName,
									new File(target).getAbsolutePath());
						}

					}

				} else {

					if (item.getFieldName().startsWith("input-")) {
						String key = item.getFieldName().replace("input-", "");

						String value = Streams.asString(item.openStream());
						if (!props.containsKey(key)) {
							// don't override uploaded files
							props.put(key, value);
						}

					}

				}

			}

		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}

		Map<String, String> params = new HashMap<String, String>();

		for (WdlParameter input : app.getMapred().getInputs()) {
			if (props.containsKey(input.getId())) {
				if (input.getType().equals("checkbox")) {
					params.put(input.getId(), input.getValues().get("true"));
				} else {
					params.put(input.getId(), props.get(input.getId()));
				}
			} else {
				if (input.getType().equals("checkbox")) {
					params.put(input.getId(), input.getValues().get("false"));
				}
			}
		}

		return params;
	}

	private FileItemIterator parseRequest(Representation entity)
			throws FileUploadException, IOException {

		// 1/ Create a factory for disk-based file items
		DiskFileItemFactory factory = new DiskFileItemFactory();
		factory.setSizeThreshold(1000240);

		// 2/ Create a new file upload handler based on the Restlet
		// FileUpload extension that will parse Restlet requests and
		// generates FileItems.
		RestletFileUpload upload = new RestletFileUpload(factory);

		return upload.getItemIterator(entity);

	}
}
