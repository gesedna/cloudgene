package cloudgene.mapred.jobs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import cloudgene.mapred.core.User;
import cloudgene.mapred.database.JobDao;
import cloudgene.mapred.jobs.queue.Queue;

public class WorkflowEngine implements Runnable {

	static WorkflowEngine instance = null;

	public static final int THREADS_LTQ = 5;

	public static final int THREADS_STQ = 5;

	private Queue longTimeQueue;

	private Queue shortTimeQueue;

	private JobDao dao;

	private boolean running = false;

	private static final Log log = LogFactory.getLog(WorkflowEngine.class);

	public static WorkflowEngine getInstance() {
		if (instance == null) {
			instance = new WorkflowEngine();
		}
		return instance;
	}

	private WorkflowEngine() {

		dao = new JobDao();

		shortTimeQueue = new Queue("ShortTimeQueue", THREADS_STQ) {

			@Override
			public Runnable createRunnable(AbstractJob job) {
				return new SetupThread(job);
			}

			@Override
			public void onComplete(AbstractJob job) {

				if (job.isSetupComplete()) {

					job.setState(AbstractJob.STATE_WAITING);
					longTimeQueue.submit(job);

				} else {

					dao.insert(job);

					log.info("Setup failed for Job " + job.getId()
							+ ". Not added to Long Time Queue.");
				}

			}

		};

		longTimeQueue = new Queue("LongTimeQueue", THREADS_LTQ) {

			@Override
			public Runnable createRunnable(AbstractJob job) {
				return job;
			}

			@Override
			public void onComplete(AbstractJob job) {

				dao.insert(job);

			}

		};

	}

	public void submit(AbstractJob job) {

		job.afterSubmission();
		shortTimeQueue.submit(job);

	}

	public void cancel(AbstractJob job) {

		if (shortTimeQueue.isInQueue(job)) {
			job.setStartTime(System.currentTimeMillis());
			shortTimeQueue.cancel(job);
		}

		if (longTimeQueue.isInQueue(job)) {
			longTimeQueue.cancel(job);
		}

	}

	@Override
	public void run() {

		new Thread(longTimeQueue).start();
		new Thread(shortTimeQueue).start();
		running = true;

	}

	public void block() {
		shortTimeQueue.pause();
		longTimeQueue.pause();
		running = false;
	}

	public void resume() {
		shortTimeQueue.resume();
		longTimeQueue.resume();
		running = true;
	}

	public boolean isRunning() {
		return running && shortTimeQueue.isRunning()
				&& longTimeQueue.isRunning();
	}

	public int getActiveCount() {
		return shortTimeQueue.getActiveCount() + longTimeQueue.getActiveCount();
	}

	public AbstractJob getJobById(String id) {
		AbstractJob job = longTimeQueue.getJobById(id);
		if (job == null) {
			job = shortTimeQueue.getJobById(id);
		}
		return job;
	}

	public Map<String, Long> getCounters(int state) {
		Map<String, Long> result = new HashMap<String, Long>();
		List<AbstractJob> jobs = longTimeQueue.getAllJobs();
		for (AbstractJob job : jobs) {
			if (job.getState() == state) {
				Map<String, Integer> counters = job.getContext().getCounters();
				for (String name : counters.keySet()) {
					Integer value = counters.get(name);
					Long oldvalue = result.get(name);
					if (oldvalue == null) {
						oldvalue = new Long(0);
					}
					result.put(name, oldvalue + value);
				}
			}
		}
		return result;
	}

	public List<AbstractJob> getJobsByUser(User user) {

		List<AbstractJob> jobs = shortTimeQueue.getJobsByUser(user);
		jobs.addAll(longTimeQueue.getJobsByUser(user));

		for (AbstractJob job : jobs) {

			if (job instanceof CloudgeneJob) {

				((CloudgeneJob) job).updateProgress();

			}

		}

		return jobs;
	}

	public List<AbstractJob> getAllJobsInShortTimeQueue() {

		List<AbstractJob> jobs = shortTimeQueue.getAllJobs();

		for (AbstractJob job : jobs) {

			if (job instanceof CloudgeneJob) {

				((CloudgeneJob) job).updateProgress();

			}

		}

		return shortTimeQueue.getAllJobs();
	}

	public List<AbstractJob> getAllJobsInLongTimeQueue() {

		List<AbstractJob> jobs = longTimeQueue.getAllJobs();

		for (AbstractJob job : jobs) {

			if (job instanceof CloudgeneJob) {

				((CloudgeneJob) job).updateProgress();

			}

		}

		return jobs;
	}

	public int getPositionInQueue(AbstractJob job) {
		return longTimeQueue.getPositionInQueue(job);
	}

	class SetupThread implements Runnable {

		private AbstractJob job;

		public SetupThread(AbstractJob job) {
			this.job = job;
		}

		@Override
		public void run() {
			log.info("Start iput validation for job " + job.getId() + "...");
			boolean result = job.executeSetup();
			job.setSetupComplete(result);
			log.info("Input Validation for job " + job.getId()
					+ " finished. Result: " + result);

		}

	}

}
