/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.twirl.gradle;

import java.util.Map;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.lambdas.SerializableLambdas;
import org.gradle.api.internal.tasks.DefaultSourceSet;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.scala.ScalaBasePlugin;
import org.gradle.api.tasks.ScalaSourceDirectorySet;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.util.GradleVersion;
import play.twirl.gradle.internal.DefaultTwirlSourceDirectorySet;
import play.twirl.gradle.internal.Gradle7TwirlSourceDirectorySet;

/** A Gradle plugin to compile Twirl templates. */
public class TwirlPlugin implements Plugin<Project> {

  static final String DEFAULT_SCALA_VERSION = "2.13";

  private static final Map<String, String> DEFAULT_TEMPLATE_FORMATS =
      Map.of(
          "html",
          "play.twirl.api.HtmlFormat",
          "txt",
          "play.twirl.api.TxtFormat",
          "xml",
          "play.twirl.api.XmlFormat",
          "js",
          "play.twirl.api.JavaScriptFormat");

  private final ObjectFactory objectFactory;

  @Inject
  public TwirlPlugin(ObjectFactory objectFactory) {
    this.objectFactory = objectFactory;
  }

  @Override
  public void apply(final Project project) {
    project.getPluginManager().apply(ScalaBasePlugin.class);

    TwirlExtension twirlExtension = project.getExtensions().create("twirl", TwirlExtension.class);
    twirlExtension.getScalaVersion().convention(DEFAULT_SCALA_VERSION);

    Configuration twirlConfiguration = createDefaultTwirlConfiguration(project, twirlExtension);

    configureSourceSetDefaults(project, twirlConfiguration);
  }

  /** Get Twirl compiler version from Gradle Plugin MANIFEST.MF */
  private String getDefaultTwirlVersion() {
    return System.getProperty("twirl.version", getClass().getPackage().getImplementationVersion());
  }

  private Configuration createDefaultTwirlConfiguration(
      Project project, TwirlExtension twirlExtension) {
    Configuration conf = project.getConfigurations().create("twirl");
    conf.setDescription("The Twirl compiler library.");
    conf.setVisible(false);
    conf.setTransitive(true);
    conf.setCanBeConsumed(false);
    conf.defaultDependencies(
        dependencies -> {
          Dependency twirlCompiler =
              project
                  .getDependencies()
                  .create(
                      String.format(
                          "org.playframework.twirl:twirl-compiler_%s:%s",
                          twirlExtension.getScalaVersion().get(), getDefaultTwirlVersion()));
          dependencies.add(twirlCompiler);
        });
    return conf;
  }

  private void configureSourceSetDefaults(
      final Project project, final Configuration twirlConfiguration) {
    javaPluginExtension(project)
        .getSourceSets()
        .all(
            (sourceSet) -> {
              TwirlSourceDirectorySet twirlSource = getTwirlSourceDirectorySet(sourceSet);
              sourceSet.getExtensions().add(TwirlSourceDirectorySet.class, "twirl", twirlSource);
              twirlSource.srcDir(project.file("src/" + sourceSet.getName() + "/twirl"));
              // See details https://github.com/playframework/twirl/issues/948
              final FileTree twirlSourceFileTree = twirlSource;
              sourceSet
                  .getResources()
                  .getFilter()
                  .exclude(
                      SerializableLambdas.spec(
                          (element) -> twirlSourceFileTree.contains(element.getFile())));
              sourceSet.getAllJava().source(twirlSource);
              sourceSet.getAllSource().source(twirlSource);

              TaskProvider<TwirlCompile> twirlTask =
                  createTwirlCompileTask(project, sourceSet, twirlSource, twirlConfiguration);

              extensionOf(sourceSet, ScalaSourceDirectorySet.class).srcDir(twirlTask);
            });
  }

  private TaskProvider<TwirlCompile> createTwirlCompileTask(
      final Project project,
      final SourceSet sourceSet,
      TwirlSourceDirectorySet twirlSource,
      final Configuration twirlConfiguration) {
    return project
        .getTasks()
        .register(
            sourceSet.getCompileTaskName("twirl"),
            TwirlCompile.class,
            twirlCompile -> {
              twirlCompile.setDescription("Compiles the " + twirlSource + ".");
              twirlCompile.getTwirlClasspath().setFrom(twirlConfiguration);
              twirlCompile.getSource().setFrom(twirlSource);
              twirlCompile.getTemplateFormats().convention(twirlSource.getTemplateFormats());
              twirlCompile.getTemplateImports().convention(twirlSource.getTemplateImports());
              twirlCompile.getSourceEncoding().convention(twirlSource.getSourceEncoding());
              twirlCompile
                  .getConstructorAnnotations()
                  .convention(twirlSource.getConstructorAnnotations());
              DirectoryProperty buildDirectory = project.getLayout().getBuildDirectory();
              twirlCompile
                  .getDestinationDirectory()
                  .convention(
                      buildDirectory.dir(
                          "generated/sources/"
                              + twirlSource.getName()
                              + "/"
                              + sourceSet.getName()));
            });
  }

  @SuppressWarnings("deprecation")
  private TwirlSourceDirectorySet getTwirlSourceDirectorySet(SourceSet sourceSet) {
    String displayName = ((DefaultSourceSet) sourceSet).getDisplayName();
    TwirlSourceDirectorySet twirlSourceDirectorySet =
        objectFactory.newInstance(
            isGradleVersionLessThan("8.0")
                ? Gradle7TwirlSourceDirectorySet.class
                : DefaultTwirlSourceDirectorySet.class,
            objectFactory.sourceDirectorySet("twirl", displayName + " Twirl source"));
    twirlSourceDirectorySet.getFilter().include("**/*.scala.*");
    twirlSourceDirectorySet.getTemplateFormats().convention(DEFAULT_TEMPLATE_FORMATS);
    return twirlSourceDirectorySet;
  }

  static boolean isGradleVersionLessThan(String gradleVersion) {
    return GradleVersion.current().compareTo(GradleVersion.version(gradleVersion)) < 0;
  }

  static JavaPluginExtension javaPluginExtension(Project project) {
    return extensionOf(project, JavaPluginExtension.class);
  }

  private static <T> T extensionOf(ExtensionAware extensionAware, Class<T> type) {
    return extensionAware.getExtensions().getByType(type);
  }
}
