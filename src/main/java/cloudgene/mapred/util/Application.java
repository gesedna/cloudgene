package cloudgene.mapred.util;

import java.io.IOException;

import cloudgene.mapred.wdl.WdlApp;
import cloudgene.mapred.wdl.WdlReader;

public class Application {

	private String filename;

	private String permission;

	private String id;

	private boolean syntaxError = false;

	private WdlApp workflow = null;

	private String errorMessage = "";
	
	private boolean enabled = true;

	public Application(String id, String permission, String filename) {
		this.id = id;
		this.permission = permission;
		this.filename = filename;
	}

	public Application() {

	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public String getPermission() {
		return permission;
	}

	public void setPermission(String permission) {
		this.permission = permission;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public void loadWorkflow() throws IOException {
		try {
			workflow = WdlReader.loadAppFromFile(getFilename());
			syntaxError = false;
			errorMessage = "";
		} catch (IOException e) {
			syntaxError = true;
			workflow = null;
			errorMessage = e.getMessage();
			throw e;
		}
	}

	public WdlApp getWorkflow() {
		return workflow;
	}

	public boolean hasSyntaxError() {
		return syntaxError;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public boolean isLoaded() {
		return workflow != null;
	}
	
	public boolean isEnabled() {
		return enabled;
	}
	
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

}
