package dev.jbang.source;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.jbang.dependencies.ArtifactInfo;
import dev.jbang.dependencies.DependencyUtil;
import dev.jbang.dependencies.ModularClassPath;
import dev.jbang.util.JavaUtil;
import dev.jbang.util.Util;

/**
 * A Jar represents a Source (something runnable) in the form of a JAR file.
 * It's a reference to an already existing JAR file, either as a solitary file
 * on the user's file system or accessible via a URL. But it can also be a Maven
 * GAV (group:artifact:version) reference resolving to a JAR file in the Maven
 * cache (~/.m2/repository).
 *
 * NB: The Jar contains/returns no other information than that which can be
 * extracted from the JAR file itself. So all Jars that refer to the same JAR
 * file will contain/return the exact same information.
 */
public class JarSource implements Source {
	private final ResourceRef resourceRef;
	private final File jarFile;

	// Cached values
	private String classPath;
	private String mainClass;
	private List<String> javaRuntimeOptions;
	private int buildJdk;
	private ScriptSource scriptSource;

	private JarSource(ResourceRef resourceRef, File jar) {
		this.resourceRef = resourceRef;
		this.jarFile = jar;
		this.javaRuntimeOptions = Collections.emptyList();
		if (jar.exists()) {
			try (JarFile jf = new JarFile(jar)) {
				Attributes attrs = jf.getManifest().getMainAttributes();
				mainClass = attrs.getValue(Attributes.Name.MAIN_CLASS);

				String val = attrs.getValue(Source.ATTR_JBANG_JAVA_OPTIONS);
				if (val != null) {
					javaRuntimeOptions = Source.quotedStringToList(val);
				}

				String ver = attrs.getValue(Source.ATTR_BUILD_JDK);
				if (ver != null) {
					buildJdk = JavaUtil.parseJavaVersion(ver);
				}

				classPath = attrs.getValue(Attributes.Name.CLASS_PATH);
			} catch (IOException e) {
				Util.warnMsg("Problem reading manifest from " + getResourceRef().getFile());
			}
		}
	}

	@Override
	public ResourceRef getResourceRef() {
		return resourceRef;
	}

	@Override
	public File getJarFile() {
		return jarFile;
	}

	@Override
	public JarSource asJarSource() {
		return this;
	}

	@Override
	public ScriptSource asScriptSource() {
		if (scriptSource == null) {
			scriptSource = ScriptSource.prepareScript(resourceRef, null);
		}
		return scriptSource;
	}

	/**
	 * Determines if the associated jar is up-to-date, returns false if it needs to
	 * be rebuilt
	 */
	public boolean isUpToDate() {
		return jarFile != null && jarFile.exists() && resolveClassPath(Collections.emptyList()).isValid();
	}

	@Override
	public List<String> getAllDependencies() {
		return Collections.emptyList();
	}

	@Override
	public ModularClassPath resolveClassPath(List<String> additionalDeps) {
		ModularClassPath mcp;
		if (resourceRef.getOriginalResource() != null
				&& DependencyUtil.looksLikeAGav(resourceRef.getOriginalResource())) {
			List<String> dependencies = new ArrayList<>(additionalDeps);
			dependencies.add(resourceRef.getOriginalResource());
			mcp = DependencyUtil.resolveDependencies(dependencies,
					Collections.emptyList(), Util.isOffline(), Util.isFresh(), !Util.isQuiet());
		} else if (classPath != null) {
			ModularClassPath mcp2 = DependencyUtil.resolveDependencies(additionalDeps,
					Collections.emptyList(), Util.isOffline(), Util.isFresh(), !Util.isQuiet());
			ModularClassPath mcp3 = ModularClassPath.fromManifestClasspath(classPath);
			List<ArtifactInfo> arts = Stream.concat(mcp2.getArtifacts().stream(), mcp3.getArtifacts().stream())
											.collect(Collectors.toList());
			mcp = new ModularClassPath(arts);
		} else if (!additionalDeps.isEmpty()) {
			mcp = DependencyUtil.resolveDependencies(additionalDeps,
					Collections.emptyList(), Util.isOffline(), Util.isFresh(), !Util.isQuiet());
		} else {
			mcp = new ModularClassPath(Collections.emptyList());
		}
		return mcp;
	}

	@Override
	public String getJavaVersion() {
		return buildJdk + "+";
	}

	@Override
	public String getMainClass() {
		return mainClass;
	}

	@Override
	public List<String> getRuntimeOptions() {
		return javaRuntimeOptions;
	}

	@Override
	public boolean isCreatedJar() {
		return false;
	}

	public static JarSource prepareJar(ResourceRef resourceRef) {
		return new JarSource(resourceRef, resourceRef.getFile());
	}

	public static JarSource prepareJar(ResourceRef resourceRef, File jarFile) {
		return new JarSource(resourceRef, jarFile);
	}

	public static JarSource prepareJar(ScriptSource ssrc) {
		JarSource jsrc = new JarSource(ssrc.getResourceRef(), ssrc.getJarFile());
		jsrc.scriptSource = ssrc;
		return jsrc;
	}
}
