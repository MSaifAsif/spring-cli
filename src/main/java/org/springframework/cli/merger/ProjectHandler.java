package org.springframework.cli.merger;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.tools.ant.util.FileUtils;
import org.codehaus.plexus.util.DirectoryScanner;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Result;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.tree.Xml.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cli.SpringCliException;
import org.springframework.cli.config.SpringCliUserConfig;
import org.springframework.cli.config.SpringCliUserConfig.CommandDefaults;
import org.springframework.cli.config.SpringCliUserConfig.ProjectCatalog;
import org.springframework.cli.config.SpringCliUserConfig.ProjectRepositories;
import org.springframework.cli.config.SpringCliUserConfig.ProjectRepository;
import org.springframework.cli.git.SourceRepositoryService;
import org.springframework.cli.recipe.RecipeUtils;
import org.springframework.cli.support.configfile.YamlConfigFile;
import org.springframework.cli.util.IoUtils;
import org.springframework.cli.util.PackageNameUtils;
import org.springframework.cli.util.PomUpdateUtils;
import org.springframework.cli.util.ProjectInfo;
import org.springframework.cli.util.RefactorUtils;
import org.springframework.cli.util.RootPackageFinder;
import org.springframework.cli.util.TerminalMessage;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;

/**
 * Contain features to create and modify projects. This is kept outside
 * of terminal classes to make things easier to test.
 *
 * @author Mark Pollack
 * @author Janne Valkealahti
 */
public class ProjectHandler {

	private static final Logger logger = LoggerFactory.getLogger(ProjectHandler.class);
	private static final String FALLBACK_DEFAULT_REPO_URL = "https://github.com/rd-1-2022/rest-service";
	private static final String FALLBACK_DEFAULT_PROJECT_NAME = "demo";

	private final SpringCliUserConfig springCliUserConfig;
	private final SourceRepositoryService sourceRepositoryService;

	private final TerminalMessage terminalMessage;

	/**
	 * Creates a project handler.
	 *
	 * @param springCliUserConfig the user config
	 * @param sourceRepositoryService the repo service
	 * @param terminalMessage the terminal to write user messages to
	 */
	public ProjectHandler(SpringCliUserConfig springCliUserConfig, SourceRepositoryService sourceRepositoryService,
			TerminalMessage terminalMessage) {
		Assert.notNull(springCliUserConfig, "springCliUserConfig must be set");
		Assert.notNull(sourceRepositoryService, "sourceRepositoryService must be set");
		Assert.notNull(terminalMessage, "terminalMessage must be set");
		this.springCliUserConfig = springCliUserConfig;
		this.sourceRepositoryService = sourceRepositoryService;
		this.terminalMessage = terminalMessage;;
	}

	/**
	 * Create a new project
	 * @param from the URL or the name of a registered project
	 * @param path the path where the new project will be created
	 * @param projectInfo project info such as GAV, name, description
	 */
	public void create(String from, String path, ProjectInfo projectInfo) {

		// Determine the URL to use
		String urlToUse;

		// if from is empty ,e.g. spring boot new, then the Url is the FALLBACK_DEFAULT
		// and the name is derived from the URL
		if (!StringUtils.hasText(from)) {
			urlToUse = FALLBACK_DEFAULT_REPO_URL;
		} else {
			// if from is not empty, then get a URL,
			// possibly translating from a project name that is already registered
			urlToUse = getProjectRepositoryUrl(from);
		}

		String directoryNameToUse;
		if (StringUtils.hasText(projectInfo.getName())) {
			directoryNameToUse = projectInfo.getName();
		} else {
			directoryNameToUse = getProjectNameUsingFrom(urlToUse);
		}

		AttributedStringBuilder sb = new AttributedStringBuilder();
		sb.style(sb.style().foreground(AttributedStyle.GREEN));
		sb.append("Getting ");
		sb.style(sb.style().foreground(AttributedStyle.WHITE));
		sb.append("project from " + urlToUse);
		terminalMessage.print(sb.toAttributedString());

		createFromUrl(IoUtils.getProjectPath(path), directoryNameToUse, urlToUse, projectInfo);
	}

	/**
	 * Adds and merges a project.
	 *
	 * @param from the from
	 * @param path the project path
	 */
	public void add(String from, String path) {
		// Will return string or throw exception
		String urlToUse = getProjectRepositoryUrl(from);
		// Will return string
		String projectName = getProjectNameUsingFrom(from);

		AttributedStringBuilder sb = new AttributedStringBuilder();
		sb.style(sb.style().foreground(AttributedStyle.WHITE));
		sb.append("Getting project with URL " + urlToUse);
		this.terminalMessage.print(sb.toAttributedString());


		Path repositoryContentsPath = sourceRepositoryService.retrieveRepositoryContents(urlToUse);
		Path projectDir = IoUtils.getProjectPath(path);
		Path workingPath = projectDir != null ? projectDir : IoUtils.getWorkingDirectory();

		ProjectMerger projectMerger = new ProjectMerger(repositoryContentsPath, workingPath, projectName, this.terminalMessage);
		projectMerger.merge();
		try {
			FileSystemUtils.deleteRecursively(repositoryContentsPath);
		} catch (IOException ex) {
			logger.warn("Could not delete path " + repositoryContentsPath, ex);
		}
		sb = new AttributedStringBuilder();
		sb.style(sb.style().foreground(AttributedStyle.GREEN));
		sb.append(System.lineSeparator());
		sb.append("Done!");
		terminalMessage.print(sb.toAttributedString());
	}

	private String getProjectNameUsingFrom(String from) {
		// Check it if is a URL, then use just the last part of the name as the 'project name'
		try {
			if (from.startsWith("https:")) {
				URL url = new URL(from);
				return new File(url.getPath()).getName();
			}
		} catch (MalformedURLException ex) {
			throw new SpringCliException("Malformed URL " + from, ex);
		}

		// We don't have a URL, but a name, let's check that it can be resolved to a URL
		findUrlFromProjectName(from);
		// The name is valid, return name
		return from;
	}

	private void replaceString(String projectName, Optional<ProjectInfo> projectInfo, File destFile,
			List<String> replacedLines, String originalLine) {
		boolean replaced = false;
		if (projectInfo.isPresent() && originalLine.contains(projectInfo.get().getName())) {
			replaced = true;
			// can only replace one token per line with this algorithm
			String processedLine = originalLine.replace(projectInfo.get().getName(), projectName);
			replacedLines.add(processedLine);
			logger.debug("In file " + destFile.getAbsolutePath() + " replaced " + projectInfo.get().getName() + " with "
					+ projectName);
			logger.debug("Processed line = " + processedLine);
		}
		if (!replaced) {
			replacedLines.add(originalLine);
		}
	}

	private Optional<String> getRootPackageName(Path workingPath) {
		// Derive fromPackage using location of existing @SpringBootApplication class.
		// TODO warning if find multiple @SpringBootApplication classes.
		logger.debug("Looking for @SpringBootApplication in directory " + workingPath.toFile());
		Optional<String> rootPackage = RootPackageFinder.findRootPackage(workingPath.toFile());
		if (rootPackage.isEmpty()) {
			AttributedStringBuilder sb = new AttributedStringBuilder();
			sb.style(sb.style().foreground(AttributedStyle.YELLOW));
			sb.append("Could find root package containing class with @SpringBootApplication.  No Java Package refactoring on the project will occur.");
			terminalMessage.print(sb.toAttributedString());
			return Optional.empty();
		}

		return rootPackage;
	}

	private Path getProjectDirectoryFromProjectName(Path projectDir, String projectName) {
		Path workingPath = projectDir != null ? projectDir : IoUtils.getWorkingDirectory();
		Path projectDirectoryPath = Paths.get(workingPath.toString(), projectName);
		if (Files.exists(projectDirectoryPath) && Files.isDirectory(projectDirectoryPath)) {
			throw new SpringCliException(
					"Directory named " + projectName + " already exists.  Choose another name.");
		}
		return projectDirectoryPath;
	}

	private Path createProjectDirectory(Path path, String directoryName) {
		Path projectDirectory = getProjectDirectoryFromProjectName(path, directoryName);
		IoUtils.createDirectory(projectDirectory);
		logger.debug("Created directory " + projectDirectory);
		return projectDirectory;
	}

	private void createFromUrl(Path projectDir, String directoryName, String url, ProjectInfo projectInfo) {
		logger.debug("Generating project from url {} with ProjectInfo {} ", url, projectInfo);
		File toDir = createProjectDirectory(projectDir, directoryName).toFile();
		Path repositoryContentsPath = sourceRepositoryService.retrieveRepositoryContents(url);

		// Get existing package name
		Optional<String> existingPackageName = this.getRootPackageName(repositoryContentsPath);

		// Refactor package name if have both a new package name and can identify the package name in newly cloned project
		if (StringUtils.hasText(projectInfo.getPackageName()) && existingPackageName.isPresent()) {
			AttributedStringBuilder sb = new AttributedStringBuilder();
			sb.style(sb.style().foreground(AttributedStyle.GREEN));
			sb.append("Refactoring ");
			sb.style(sb.style().foreground(AttributedStyle.WHITE));
			sb.append("package to " + projectInfo.getPackageName());
			terminalMessage.print(sb.toAttributedString());
			RefactorUtils.refactorPackage(projectInfo.getPackageName(), existingPackageName.get(), repositoryContentsPath);
		}


		// Update GroupId, ArtfiactId, Version, name, Description as needed.
		PomUpdateUtils pomUpdateUtils = new PomUpdateUtils();
		pomUpdateUtils.updatePom(repositoryContentsPath, projectInfo);


		// Copy files
		File fromDir = repositoryContentsPath.toFile();

		DirectoryScanner ds = new DirectoryScanner();
		ds.setBasedir(fromDir);
		ds.scan();
		String[] fileNames = ds.getIncludedFiles();

		toDir.mkdirs();
		for (String fileName : fileNames) {
			File srcFile = new File(fromDir, fileName);
			File destFile = new File(toDir, fileName);
			logger.debug("Copy from " + srcFile + " to " + destFile);
			try {
				FileUtils.getFileUtils().copyFile(srcFile, destFile);
				if (srcFile.canExecute()) {
					destFile.setExecutable(true);
				}
			} catch (IOException e) {
				throw new SpringCliException(
						"Could not copy files from " + fromDir.getAbsolutePath() + " to " + toDir.getAbsolutePath());
			}
		}
		try {
			FileSystemUtils.deleteRecursively(repositoryContentsPath);
		} catch (IOException ex) {
			logger.warn("Could not delete path " + repositoryContentsPath, ex);
		}

		AttributedStringBuilder sb = new AttributedStringBuilder();
		sb.style(sb.style().foreground(AttributedStyle.GREEN));
		sb.append("Created ");
		sb.style(sb.style().foreground(AttributedStyle.WHITE));
		sb.append("project in directory '" + toDir.getName() + "'");
		terminalMessage.print(sb.toAttributedString());

	}

	private String getProjectName(String commandName, String subCommandName, String optionProjectNameValue) {
		if (StringUtils.hasText(optionProjectNameValue)) {
			return optionProjectNameValue;
		}
		CommandDefaults commandDefaults = this.springCliUserConfig.getCommandDefaults();
		Optional<String> newProjectName = commandDefaults.findDefaultOptionValue(commandName, subCommandName, "name");
		if (newProjectName.isPresent()) {
			return newProjectName.get().replaceAll(" ", "_");
		} else {
			return FALLBACK_DEFAULT_PROJECT_NAME;
		}
	}

	private Optional<String> getPackageName(String commandName, String subCommandName, String packageName) {
		// If user provided, make sure it is sanitized
		if (StringUtils.hasText(packageName)) {
			return Optional.of(PackageNameUtils.getTargetPackageName(packageName, packageName));
		}
		// Check for the default value of package-name that was set using "config set boot new"
		CommandDefaults commandDefaults = this.springCliUserConfig.getCommandDefaults();
		return commandDefaults.findDefaultOptionValue(commandName, subCommandName, "package-name");
	}

	@Nullable
	private String findUrlFromProjectRepositories(String projectName,
			Collection<ProjectRepository> projectRepositories) {
		for (ProjectRepository projectRepository : projectRepositories) {
			if (projectName.trim().equalsIgnoreCase(projectRepository.getName().trim())) {
				// match - get url
				String url = projectRepository.getUrl();
				if (StringUtils.hasText(url)) {
					return url;
				}
				break;
			}
		}
		return null;
	}

	@Nullable
	private String findUrlFromProjectName(String projectName) {
		Collection<ProjectRepository> projectRepositories = springCliUserConfig.getProjectRepositories()
				.getProjectRepositories();
		if (projectRepositories != null) {
			String url = findUrlFromProjectRepositories(projectName, projectRepositories);
			if (url != null) return url;
		}

		List<ProjectCatalog> projectCatalogs = springCliUserConfig.getProjectCatalogs().getProjectCatalogs();
		if (projectCatalogs != null) {
			for (ProjectCatalog projectCatalog : projectCatalogs) {
				String url = projectCatalog.getUrl();
				Path path = sourceRepositoryService.retrieveRepositoryContents(url);
				YamlConfigFile yamlConfigFile = new YamlConfigFile();
				projectRepositories = yamlConfigFile.read(Paths.get(path.toString(), "project-repositories.yml"),
						ProjectRepositories.class).getProjectRepositories();
				try {
					FileSystemUtils.deleteRecursively(path);
				} catch (IOException ex) {
					logger.warn("Could not delete path " + path, ex);
				}
				url = findUrlFromProjectRepositories(projectName, projectRepositories);
				if (url != null) return url;
			}
		}

		throw new SpringCliException("Could not resolve project name " + projectName
				+ " to URL.  The command `project list` shows the available project names.");
	}

	@Nullable
	private String getProjectRepositoryUrl(String from) {
		// Check it if is a URL
		if (from.startsWith("https:")) {
			return from;
		} else {
			// look up url based on name
			return findUrlFromProjectName(from);
		}
	}

}
