package com.efenglu.japicc.plugin;

import com.efenglu.japicc.annotations.SkipComplianceCheck;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.io.URLInputStreamFacade;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A tool for checking backward binary and source-level compatibility of a Java library API.  The tool checks classes
 * declarations of previous and new versions and analyzes changes that may break compatibility: removed methods,
 * removed class fields, added abstract methods, etc. The tool is intended for developers of software libraries
 * who are interested in ensuring backward compatibility.
 */
@Mojo(name = "check",
        defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true)
public class JapiccMojo extends AbstractMojo {
    /**
     * Previous Library Artifact ID to compare against
     */
    @Parameter(defaultValue = "${project.artifactId}", property = "japicc.previousArtifactId", required = true)
    private String previousArtifactId;

    /**
     * Previous Library Artifact ID to compare against
     */
    @Parameter(defaultValue = "${project.groupId}", property = "japicc.previousGroupId", required = true)
    private String previousGroupId;

    /**
     * Previous Library Artifact Version to compare against
     */
    @Parameter(property = "japicc.previousVersion")
    private String previousVersion;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    private MojoExecution mojo;

    @Parameter(defaultValue = "${plugin}", readonly = true) // Maven 3 only
    private PluginDescriptor plugin;

    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File basedir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File target;

    /**
     * Skip execution
     */
    @Parameter(defaultValue = "false", property = "japicc.skip")
    private boolean skip;

    /**
     * The entry point to Aether, i.e. the component doing all the work.
     */
    @Component
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of plugins and their dependencies.
     */
    @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    /**
     * Fail the build on error
     */
    @Parameter(defaultValue = "true", property = "japicc.failOnError")
    private boolean failOnError = true;

    /**
     * -keep-internal
     * Do NOT skip checking of these packages:
     * impl*
     * internal*
     * examples*
     */
    @Parameter(defaultValue = "false", property = "japicc.keepInternal")
    private boolean keepInternal = false;

    /**
     * -skip-internal-packages PATTERN
     * Do not check packages matched by the pattern.
     */
    @Parameter(property = "japicc.skipInternalPackages")
    private String skipInternalPackages = null;

    /**
     * -skip-internal-types PATTERN
     * Do not check types (classes and interfaces) matched by the pattern.
     */
    @Parameter(property = "japicc.skipInternalTypes")
    private String skipInternalTypes = null;

    /**
     * -classes-list PATH
     * This option allows to specify a file with a list
     * of classes that should be checked, other classes will not be checked.
     */
    @Parameter(property = "japicc.classesList")
    private File classesList;

    /**
     * -annotations-list
     * List of annotations to be included
     * Other classes will not be checked.
     */
    @Parameter
    private List<String> annotationsList;

    /**
     * -skip-annotations-list
     * List of annotations to be skipped
     * Skip checking of classes annotated by the annotations in the list.
     */
    @Parameter
    private List<String> skipAnnotationsList;

    /**
     * -skip-deprecated
     * Skip analysis of deprecated methods and classes.
     */
    @Parameter(defaultValue = "true", property = "japicc.skipDeprecated")
    private boolean skipDeprecated = true;

    /**
     * -skip-classes PATH
     * This option allows to specify a file with a list
     * of classes that should not be checked.
     */
    @Parameter(property = "japicc.skipClasses")
    private File skipClasses = null;

    /**
     * -skip-packages PATH
     * This option allows to specify a file with a list
     * of packages that should not be checked.
     */
    @Parameter(property = "japicc.skipPackages")
    private File skipPackages = null;

    /**
     * -report-path PATH
     * Path to compatibility report.
     * Default:
     * ${project.build.directory}/site/japicc/compat_report.html
     */
    @Parameter(defaultValue = "${project.build.directory}/site/japicc/compat_report.html", property = "japicc.reportPath")
    private File reportPath;

    /**
     * -bin-report-path PATH
     * Path to "Binary" compatibility report.
     * Default:
     * ${project.build.directory}/site/japicc/bin_compat_report.html
     */
    @Parameter(defaultValue = "${project.build.directory}/site/japicc/bin_compat_report.html", property = "japicc.binReportPath")
    private File binReportPath;

    /**
     * -src-report-path PATH
     * Path to "Source" compatibility report.
     * Default:
     * ${project.build.directory}/site/japicc/src_compat_report.html
     */
    @Parameter(defaultValue = "${project.build.directory}/site/japicc/src_compat_report.html", property = "japicc.srcReportRath")
    private File srcReportRath;

    /**
     * -quick
     * Quick analysis.
     * Disabled:
     * - analysis of method parameter names
     * - analysis of class field values
     * - analysis of usage of added abstract methods
     * - distinction of deprecated methods and classes
     */
    @Parameter(defaultValue = "false", property = "japicc.quick")
    private boolean quick = false;

    /**
     * -sort
     * Enable sorting of data in API dumps.
     */
    @Parameter(defaultValue = "false", property = "japicc.sort")
    private boolean sort = false;

    /**
     * -show-access
     * Show access level of non-public methods listed in the report.
     */
    @Parameter(defaultValue = "false", property = "japicc.showAccess")
    private boolean showAccess = false;

    /**
     * -hide-templates
     * Hide template parameters in the report.
     */
    @Parameter(defaultValue = "false", property = "japicc.hideTemplates")
    private boolean hideTemplates = false;

    /**
     * -show-packages
     * Show package names in the report.
     */
    @Parameter(defaultValue = "false", property = "japicc.showPackages")
    private boolean showPackages = false;

    /**
     * -limit-affected LIMIT
     * The maximum number of affected methods listed under the description
     * of the changed type in the report.
     */
    @Parameter(defaultValue = "-1", property = "japicc.limitAffected")
    private Integer limitAffected;

    /**
     * -compact
     * Try to simplify formatting and reduce size of the report (for a big set of changes).
     */
    @Parameter(defaultValue = "false", property = "japicc.compact")
    private boolean compact = false;

    /**
     * -added-annotations
     * Apply filters by annotations only to new version of the library.
     */
    @Parameter(defaultValue = "false", property = "japicc.addedAnnotations")
    private boolean addedAnnotations = false;

    /**
     * -removed-annotations
     * Apply filters by annotations only to previous version of the library.
     */
    @Parameter(defaultValue = "false", property = "japicc.removedAnnotations")
    private boolean removedAnnotations = false;

    /**
     * -jdk-path PATH
     * Path to the JDK install tree (e.g. /usr/lib/jvm/java-7-openjdk-amd64).
     */
    @Parameter(defaultValue = "${env.JAVA_HOME}", property = "japicc.jdkPath")
    private String jdkPath;

    /**
     * -title NAME
     * Change library name in the report title to NAME. By default
     * will be displayed a name specified by -l option.
     */
    @Parameter(defaultValue = "${project.name}", property = "japicc.title")
    private String title;

    /**
     * Skip compliance check if the version matches X.0.0 since it is the first version in a series
     */
    @Parameter(defaultValue = "false", property = "japicc.skipFirstInSeries")
    private boolean skipFirstInSeries;

    /**
     * Full path to perl executable
     */
    @Parameter(defaultValue = "/usr/bin/perl", property = "japicc.perlExec", required = true)
    private String perlExec;


    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!"jar".equals(project.getPackaging())) {
            getLog().info("Does not support packaging type: " + project.getPackaging() + ", skipping");
            return;
        }
        if (skip) {
            getLog().info("Skipping");
            return;
        }

        if (!canRun()) {
            if (failOnError) {
                throw new MojoExecutionException("Invalid execution environment, see log for details");
            } else {
                getLog().warn("Invalid execution environment, skipping execution");
            }
            return;
        }

        File newJarFile = new File(target, project.getBuild().getFinalName() + ".jar");
        if (!newJarFile.exists()) {
            getLog().warn("No jar previousArtifact, skipping");
            return;
        }

        String projectVersion = project.getVersion();
        String[] versionSplit = projectVersion.split("\\.");
        int majorVersion = Integer.parseInt(versionSplit[0]);

        if (majorVersion == 0) {
            getLog().info("Skipping: Pre-release previousArtifact");
            return;
        }

        String previousVersion;
        if ((StringUtils.isBlank(this.previousVersion))) {
            Artifact rangeArtifact = new DefaultArtifact(String.format(
                    "[%1$s.0.0, %2$s.0.0)",
                    majorVersion,
                    majorVersion + 1
            ));
            final VersionRangeRequest versionRangeRequest = new VersionRangeRequest(rangeArtifact, remoteRepos, null);
            final VersionRangeResult versionRangeResult;
            try {
                versionRangeResult = repoSystem.resolveVersionRange(repoSession, versionRangeRequest);
                previousVersion = versionRangeResult.getHighestVersion().toString();
            } catch (VersionRangeResolutionException e) {
                if (skipFirstInSeries) {
                    getLog().debug("Failed to resolve previous previousArtifact, assuming first in series, skipping");
                    return;
                } else {
                    throw new MojoExecutionException("Failed to resolve previous previousArtifact", e);
                }
            }
        } else {
            previousVersion = this.previousVersion;
        }

        String previousArtifactStr = MessageFormat.format("{0}:{1}:{2}", previousGroupId, previousArtifactId, previousVersion);
        Artifact previousArtifact;
        try {
            previousArtifact = new DefaultArtifact(previousArtifactStr);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Invalid previous artifact " + previousArtifactStr, e);
        }

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(previousArtifact);
        request.setRepositories(remoteRepos);

        ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, request);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Failed to resolve previous artifact " + e.getMessage(), e);
        }

        getLog().debug("Resolved previousArtifact " + previousArtifact + " to " + result.getArtifact().getFile() + " from " + result.getRepository());

        File previousJarFile = result.getArtifact().getFile();

        ProcessBuilder builder = new ProcessBuilder();
        builder.inheritIO();
        try {
            FileUtils.forceMkdir(target);
            FileUtils.forceMkdir(reportPath.getParentFile());
            FileUtils.forceMkdir(binReportPath.getParentFile());
            FileUtils.forceMkdir(srcReportRath.getParentFile());
        } catch (IOException e) {
            throw new MojoExecutionException("IO Error while creating target directory", e);
        }
        File japiccScript = getScriptFromJar();
        List<String> arguments = new ArrayList<>();
        arguments.add(perlExec);
        arguments.add(japiccScript.getAbsolutePath());
        insertAdditionalArguments(arguments);
        arguments.add(previousJarFile.getAbsolutePath());
        arguments.add(newJarFile.getAbsolutePath());
        String[] strArray = arguments.toArray(new String[0]);
        builder.command(strArray);

        getLog().debug("Executing JAPICC: " + builder.command());

        Process process = null;
        try {
            getLog().info("Checking API...");
            process = builder.start();
            int pResult = process.waitFor();
            getLog().info("DONE API Check");
            getLog().debug("JAPICC Return value: " + pResult);
            if (pResult != 0) {
                getLog().error("JAPICC Validation FAILED");
                getLog().error("Report available at: file://" + reportPath);
                if (failOnError) {
                    throw new MojoFailureException("Validation error see log for details: file://" + reportPath);
                } else {
                    getLog().warn("SKIPPING Incompatible API, failOnError: " + failOnError);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("IO Error while validating", e);
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Interrupt", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private boolean canRun() {
        File file = new File(perlExec);
        if (file.exists() && file.canExecute()) {
            return true;
        } else {
            getLog().error("Can NOT run JAPICC");
            getLog().error(perlExec + " missing or not executable");
            return false;
        }
    }

    private void insertAdditionalArguments(List<String> arguments) throws MojoExecutionException {
        insertJdkPath(arguments);
        insertTitle(arguments);
        insertKeepInternal(arguments);
        insertSkipInternalPackages(arguments);
        insertSkipInternalTypes(arguments);
        insertClassesList(arguments);
        insertAnnotations(arguments);
        insertSkipAnnotations(arguments);
        insertSkipDeprecated(arguments);
        insertSkipClasses(arguments);
        insertSkipPackages(arguments);
        insertReportPath(arguments);
        insertBinReportPath(arguments);
        insertSrcReportPath(arguments);
        insertQuick(arguments);
        insertSort(arguments);
        insertShowAccess(arguments);
        insertHideTemplates(arguments);
        insertShowPackage(arguments);
        insertLimitAffected(arguments);
        insertCompact(arguments);
        insertAddedAnnotations(arguments);
        insertRemovedAnnotations(arguments);
    }

    private void insertTitle(List<String> arguments) {
        if (StringUtils.isNotBlank(title)) {
            arguments.add("-title");
            arguments.add(title);
        }
    }

    private void insertJdkPath(List<String> arguments) {
        if (StringUtils.isNotBlank(jdkPath)) {
            arguments.add("-jdk-path");
            arguments.add(jdkPath);
        }
    }

    private void insertSkipInternalTypes(List<String> arguments) {
        if (StringUtils.isNotBlank(skipInternalTypes)) {
            arguments.add("-skip-internal-types");
            arguments.add(skipInternalTypes);
        }
    }

    private void insertRemovedAnnotations(List<String> arguments) {
        if (removedAnnotations) {
            arguments.add("-removed-annotations");
        }
    }

    private void insertAddedAnnotations(List<String> arguments) {
        if (addedAnnotations) {
            arguments.add("-added-annotations");
        }
    }

    private void insertCompact(List<String> arguments) {
        if (compact) {
            arguments.add("-compact");
        }
    }

    private void insertLimitAffected(List<String> arguments) {
        if (limitAffected != null && limitAffected > 0) {
            arguments.add("-limit-affected");
            arguments.add(String.valueOf(limitAffected));
        }
    }

    private void insertShowPackage(List<String> arguments) {
        if (showPackages) {
            arguments.add("-show-packages");
        }
    }

    private void insertHideTemplates(List<String> arguments) {
        if (hideTemplates) {
            arguments.add("-hide-templates");
        }
    }

    private void insertShowAccess(List<String> arguments) {
        if (showAccess) {
            arguments.add("-show-access");
        }
    }

    private void insertSort(List<String> arguments) {
        if (sort) {
            arguments.add("-sort");
        }
    }

    private void insertQuick(List<String> arguments) {
        if (quick) {
            arguments.add("-quick");
        }
    }

    private void insertSrcReportPath(List<String> arguments) {
        if (srcReportRath != null) {
            arguments.add("-src-report-path");
            arguments.add(srcReportRath.getAbsolutePath());
        }
    }

    private void insertBinReportPath(List<String> arguments) {
        if (binReportPath != null) {
            arguments.add("-bin-report-path");
            arguments.add(binReportPath.getAbsolutePath());
        }
    }

    private void insertReportPath(List<String> arguments) {
        if (reportPath != null) {
            arguments.add("-report-path");
            arguments.add(reportPath.getAbsolutePath());
        }
    }

    private void insertSkipPackages(List<String> arguments) {
        if (skipPackages != null) {
            arguments.add("-skip-packages");
            arguments.add(skipPackages.getAbsolutePath());
        }
    }

    private void insertSkipClasses(List<String> arguments) {
        if (skipClasses != null) {
            arguments.add("-skip-classes");
            arguments.add(skipClasses.getAbsolutePath());
        }
    }

    private void insertSkipDeprecated(List<String> arguments) {
        if (skipDeprecated) {
            arguments.add("-skip-deprecated");
        }
    }

    private void insertSkipAnnotations(List<String> arguments) throws MojoExecutionException {
        Set<String> list = new HashSet<>();
        list.add(SkipComplianceCheck.class.getName());
        if (skipAnnotationsList != null && !skipAnnotationsList.isEmpty()) {
            list.addAll(skipAnnotationsList);
        }
        if (!list.isEmpty()) {
            File file = new File(target, "japicc-skip-annotations-list");
            try {
                org.apache.commons.io.FileUtils.writeLines(file, list);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to setup Skip Annotations list file", e);
            }
            arguments.add("-skip-annotations-list");
            arguments.add(file.getAbsolutePath());
        }
    }

    private void insertAnnotations(List<String> arguments) throws MojoExecutionException {
        Set<String> list = new HashSet<>();
        if (annotationsList != null && !annotationsList.isEmpty()) {
            list.addAll(annotationsList);
        }
        if (!list.isEmpty()) {
            File file = new File(target, "japicc-annotations-list");
            try {
                org.apache.commons.io.FileUtils.writeLines(file, list);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to setup Annotations list file", e);
            }
            arguments.add("-annotations-list");
            arguments.add(file.getAbsolutePath());
        }
    }

    private void insertClassesList(List<String> arguments) {
        if (classesList != null) {
            arguments.add("-classes-list");
            arguments.add(classesList.getAbsolutePath());
        }
    }

    private void insertSkipInternalPackages(List<String> arguments) {
        if (StringUtils.isNotBlank(skipInternalPackages)) {
            arguments.add("-skip-internal-packages");
            arguments.add(skipInternalPackages);
        }
    }

    private void insertKeepInternal(List<String> arguments) {
        if (keepInternal) {
            arguments.add("-keep-internal");
        }
    }

    private synchronized File getScriptFromJar() throws MojoExecutionException {
        getLog().info("Loading JAPI Script...");
        try {
            File temp = new File(target, "japi-compliance-checker.pl");
            if (temp.exists()) {
                return temp;
            }
            FileUtils.copyStreamToFile(new URLInputStreamFacade(JapiccMojo.class.getResource("/japi-compliance-checker.pl")), temp);
            return temp;
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to load JAPICC script from jar", e);
        }
    }
}
