package cloudgene.mapred.api.v2.jobs;

import genepi.hadoop.HdfsUtil;
import genepi.io.FileUtil;

import java.util.HashMap;
import java.util.Map;

import org.restlet.representation.Representation;
import org.restlet.resource.Get;

import cloudgene.mapred.core.User;
import cloudgene.mapred.database.JobDao;
import cloudgene.mapred.jobs.AbstractJob;
import cloudgene.mapred.jobs.CloudgeneJob;
import cloudgene.mapred.util.Application;
import cloudgene.mapred.util.BaseResource;
import cloudgene.mapred.util.PublicUser;
import cloudgene.mapred.wdl.WdlApp;
import cloudgene.mapred.wdl.WdlReader;

public class RestartJob extends BaseResource {

	@Get
	public Representation get(Representation entity) {

		User user = getAuthUser();

		if (user == null) {
			user = PublicUser.getUser(getDatabase());
		}

		String id = getAttribute("job");

		if (id == null) {
			return error404("No job id specified.");
		}

		JobDao dao = new JobDao(getDatabase());
		AbstractJob job = dao.findById(id);

		if (job == null) {
			return error404("Job " + id + " not found.");
		}

		if (!user.isAdmin() && job.getUser().getId() != user.getId()) {
			return error403("Access denied.");
		}

		if (job.getState() == AbstractJob.STATE_DEAD) {

			String hdfsWorkspace = HdfsUtil.path(getSettings().getHdfsWorkspace(), id);
			String localWorkspace = FileUtil.path(getSettings().getLocalWorkspace(), id);

			job.setLocalWorkspace(localWorkspace);
			job.setHdfsWorkspace(hdfsWorkspace);
			job.setSettings(getSettings());
			job.setRemoveHdfsWorkspace(getSettings().isRemoveHdfsWorkspace());

			String application = job.getApplicationId();
			Application application2 = getSettings().getApp(job.getUser(), application);
			WdlApp app = null;
			try {
				app = application2.getWorkflow();
			} catch (Exception e1) {

				return error400(
						"Application '" + application + "' not found or the request requires user authentication.");

			}

			((CloudgeneJob) job).loadConfig(app.getMapred());

			getWorkflowEngine().restart(job);

			Map<String, Object> params = new HashMap<String, Object>();
			params.put("id", id);
			return ok("Your job was successfully added to the job queue.", params);
		} else {
			return error400("Job " + id + " is not pending.");

		}
	}

}
