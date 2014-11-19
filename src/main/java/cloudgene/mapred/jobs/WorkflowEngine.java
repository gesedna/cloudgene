package cloudgene.mapred.jobs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import cloudgene.mapred.core.User;
import cloudgene.mapred.database.CounterDao;
import cloudgene.mapred.database.DownloadDao;
import cloudgene.mapred.database.JobDao;
import cloudgene.mapred.database.MessageDao;
import cloudgene.mapred.database.ParameterDao;
import cloudgene.mapred.database.StepDao;
import cloudgene.mapred.database.util.Database;
import cloudgene.mapred.jobs.queue.Queue;

public class WorkflowEngine implements Runnable {

	public static final int THREADS_LTQ = 5;

	public static final int THREADS_STQ = 5;

	private Queue longTimeQueue;

	private Queue shortTimeQueue;

	private JobDao dao;

	private boolean running = false;

	private Database database;

	private static final Log log = LogFactory.getLog(WorkflowEngine.class);

	public WorkflowEngine(Database mdatabase) {
		this.database = mdatabase;

		dao = new JobDao(database);

		shortTimeQueue = new Queue("ShortTimeQueue", THREADS_STQ) {

			@Override
			public Runnable createRunnable(AbstractJob job) {
				return new SetupThread(job);
			}

			@Override
			public void onComplete(AbstractJob job) {

				if (job.isSetupComplete()) {

					job.setState(AbstractJob.STATE_WAITING);
					dao.update(job);
					longTimeQueue.submit(job);

				} else {

					log.info("Setup failed for Job " + job.getId()
							+ ". Not added to Long Time Queue.");

					dao.update(job);

					for (CloudgeneParameter parameter : job.getOutputParams()) {

						if (parameter.isDownload()) {

							if (((CloudgeneParameter) parameter).getFiles() != null) {

								DownloadDao downloadDao = new DownloadDao(
										database);
								for (Download download : parameter.getFiles()) {
									download.setParameterId(parameter.getId());
									download.setParameter(parameter);
									downloadDao.insert(download);
								}

							}

						}

					}

					if (job.getSteps() != null) {
						StepDao dao2 = new StepDao(database);
						for (CloudgeneStep step : job.getSteps()) {
							dao2.insert(step);

							MessageDao messageDao = new MessageDao(database);
							if (step.getLogMessages() != null) {
								for (Message logMessage : step.getLogMessages()) {
									messageDao.insert(logMessage);
								}
							}

						}
					}

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

				dao.update(job);
				for (CloudgeneParameter parameter : job.getOutputParams()) {

					if (parameter.isDownload()) {

						if (((CloudgeneParameter) parameter).getFiles() != null) {

							DownloadDao downloadDao = new DownloadDao(database);
							for (Download download : parameter.getFiles()) {
								download.setParameterId(parameter.getId());
								download.setParameter(parameter);
								downloadDao.insert(download);
							}

						}

					}

				}

				if (job.getSteps() != null) {
					StepDao dao2 = new StepDao(database);
					for (CloudgeneStep step : job.getSteps()) {
						dao2.insert(step);

						MessageDao messageDao = new MessageDao(database);
						if (step.getLogMessages() != null) {
							for (Message logMessage : step.getLogMessages()) {
								messageDao.insert(logMessage);
							}
						}

					}
				}

				// write all submitted counters into database
				for (String name : job.getContext().getSubmittedCounters()
						.keySet()) {
					Integer value = job.getContext().getSubmittedCounters()
							.get(name);

					if (value != null) {

						CounterDao dao = new CounterDao(database);
						dao.insert(name, value, job);

					}
				}

			}

		};

	}

	public void submit(AbstractJob job) {

		dao.insert(job);

		ParameterDao dao = new ParameterDao(database);

		for (CloudgeneParameter parameter : job.getInputParams()) {
			parameter.setJobId(job.getId());
			dao.insert(parameter);
		}

		for (CloudgeneParameter parameter : job.getOutputParams()) {
			parameter.setJobId(job.getId());
			dao.insert(parameter);
		}

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

	public boolean isInQueue(AbstractJob job) {
		if (!shortTimeQueue.isInQueue(job)) {
			return longTimeQueue.isInQueue(job);
		} else {
			return true;
		}
	}
}
