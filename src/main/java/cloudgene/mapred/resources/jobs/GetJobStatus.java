package cloudgene.mapred.resources.jobs;

import net.sf.json.JSONObject;

import org.restlet.data.Form;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.representation.Variant;
import org.restlet.resource.Post;

import cloudgene.mapred.core.User;
import cloudgene.mapred.database.JobDao;
import cloudgene.mapred.database.UserDao;
import cloudgene.mapred.jobs.AbstractJob;
import cloudgene.mapred.jobs.CloudgeneJob;
import cloudgene.mapred.util.BaseResource;
import cloudgene.mapred.util.JSONConverter;

public class GetJobStatus extends BaseResource {

	@Post
	protected Representation post(Representation entity, Variant variant) {

		User user = getUser(getRequest());

		Form form = new Form(entity);
		String id = form.getFirstValue("job_id");

		AbstractJob job = getWorkflowEngine().getJobById(id);

		if (job == null) {

			JobDao dao = new JobDao(getDatabase());
			job = dao.findById(id, false);

		} else {

			if (job instanceof CloudgeneJob) {

				((CloudgeneJob) job).updateProgress();

			}

			int position = getWorkflowEngine().getPositionInQueue(job);
			job.setPositionInQueue(position);

		}

		if (job == null) {
			return error404("Job " + id + " not found.");
		}

		// public mode
		if (user == null) {
			UserDao dao = new UserDao(getDatabase());
			user = dao.findByUsername("public");
		}

		if (!user.isAdmin() && job.getUser().getId() != user.getId()) {
			return error403("Access denied.");
		}

		JSONObject object = JSONConverter.fromJob(job);

		return new StringRepresentation(object.toString());

	}

}
