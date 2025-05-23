/*
 * Copyright (C) 2015, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.gitrepo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;
import org.xml.sax.SAXException;

public class ManifestParserTest {

	@Test
	public void testManifestParser() throws Exception {
		String baseUrl = "https://git.google.com/";
		StringBuilder xmlContent = new StringBuilder();
		Set<String> results = new HashSet<>();
		xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
			.append("<manifest>")
			.append("<remote name=\"remote1\" fetch=\".\" />")
			.append("<default revision=\"master\" remote=\"remote1\" />")
			.append("<project path=\"foo\" name=\"")
			.append("foo")
			.append("\" groups=\"a,test\" />")
			.append("<project path=\"bar\" name=\"")
			.append("bar")
			.append("\" groups=\"notdefault\" />")
			.append("<project path=\"foo/a\" name=\"")
			.append("a")
			.append("\" groups=\"a\" />")
			.append("<project path=\"b\" name=\"")
			.append("b")
			.append("\" groups=\"b\" />")
			.append("</manifest>");

		ManifestParser parser = new ManifestParser(
				null, null, "master", baseUrl, null, null);
		parser.read(new ByteArrayInputStream(xmlContent.toString().getBytes(UTF_8)));
		// Unfiltered projects should have them all.
		results.clear();
		results.add("foo");
		results.add("bar");
		results.add("foo/a");
		results.add("b");
		for (RepoProject proj : parser.getProjects()) {
			String msg = String.format(
					"project \"%s\" should be included in unfiltered projects",
					proj.getPath());
			assertTrue(msg, results.contains(proj.getPath()));
			results.remove(proj.getPath());
		}
		assertTrue(
				"Unfiltered projects shouldn't contain any unexpected results",
				results.isEmpty());
		// Filtered projects should have foo & b
		results.clear();
		results.add("foo");
		results.add("b");
		for (RepoProject proj : parser.getFilteredProjects()) {
			String msg = String.format(
					"project \"%s\" should be included in filtered projects",
					proj.getPath());
			assertTrue(msg, results.contains(proj.getPath()));
			results.remove(proj.getPath());
		}
		assertTrue(
				"Filtered projects shouldn't contain any unexpected results",
				results.isEmpty());
	}

	@Test
	public void testManifestParserWithMissingFetchOnRemote() throws Exception {
		String baseUrl = "https://git.google.com/";
		StringBuilder xmlContent = new StringBuilder();
		xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<manifest>")
				.append("<remote name=\"remote1\" />")
				.append("<default revision=\"master\" remote=\"remote1\" />")
				.append("<project path=\"foo\" name=\"").append("foo")
				.append("\" groups=\"a,test\" />")
				.append("<project path=\"bar\" name=\"").append("bar")
				.append("\" groups=\"notdefault\" />")
				.append("<project path=\"foo/a\" name=\"").append("a")
				.append("\" groups=\"a\" />")
				.append("<project path=\"b\" name=\"").append("b")
				.append("\" groups=\"b\" />").append("</manifest>");

		ManifestParser parser = new ManifestParser(null, null, "master",
				baseUrl, null, null);
		try {
			parser.read(new ByteArrayInputStream(
					xmlContent.toString().getBytes(UTF_8)));
			fail("ManifestParser did not throw exception for missing fetch");
		} catch (IOException e) {
			assertTrue(e.getCause() instanceof SAXException);
			assertTrue(e.getCause().getMessage()
					.contains("is missing fetch attribute"));
		}
	}

	@Test
	public void testRemoveProject() throws Exception {
		StringBuilder xmlContent = new StringBuilder();
		xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<manifest>")
				.append("<remote name=\"remote1\" fetch=\".\" />")
				.append("<default revision=\"master\" remote=\"remote1\" />")
				.append("<project path=\"foo\" name=\"foo\" />")
				.append("<project path=\"bar\" name=\"bar\" />")
				.append("<remove-project name=\"foo\" />")
				.append("<project path=\"foo\" name=\"baz\" />")
				.append("</manifest>");

		ManifestParser parser = new ManifestParser(null, null, "master",
				"https://git.google.com/", null, null);
		parser.read(new ByteArrayInputStream(
				xmlContent.toString().getBytes(UTF_8)));

		assertEquals(Stream.of("bar", "baz").collect(Collectors.toSet()),
				parser.getProjects().stream().map(RepoProject::getName)
						.collect(Collectors.toSet()));
	}

	@Test
	public void testPinProjectWithUpstream() throws Exception {
		StringBuilder xmlContent = new StringBuilder();
		xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<manifest>")
				.append("<remote name=\"remote1\" fetch=\".\" />")
				.append("<default revision=\"master\" remote=\"remote1\" />")
				.append("<project path=\"foo\" name=\"pin-with-upstream\"")
				.append("  revision=\"9b2fe85c0279f4d5ac69f07ddcd48566c3555405\"")
				.append("  upstream=\"branchX\"/>")
				.append("<project path=\"bar\" name=\"pin-without-upstream\"")
				.append("  revision=\"76ce6d91a2e07fdfcbfc8df6970c9e98a98e36a0\" />")
				.append("</manifest>");

		ManifestParser parser = new ManifestParser(null, null, "master",
				"https://git.google.com/", null, null);
		parser.read(new ByteArrayInputStream(
				xmlContent.toString().getBytes(UTF_8)));

		Map<String, RepoProject> repos = parser.getProjects().stream().collect(
				Collectors.toMap(RepoProject::getName, Function.identity()));
		assertEquals(2, repos.size());

		RepoProject foo = repos.get("pin-with-upstream");
		assertEquals("pin-with-upstream", foo.getName());
		assertEquals("9b2fe85c0279f4d5ac69f07ddcd48566c3555405",
				foo.getRevision());
		assertEquals("branchX", foo.getUpstream());

		RepoProject bar = repos.get("pin-without-upstream");
		assertEquals("pin-without-upstream", bar.getName());
		assertEquals("76ce6d91a2e07fdfcbfc8df6970c9e98a98e36a0",
				bar.getRevision());
		assertNull(bar.getUpstream());
	}

	@Test
	public void testWithDestBranch() throws Exception {
		StringBuilder xmlContent = new StringBuilder();
		xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<manifest>")
				.append("<remote name=\"remote1\" fetch=\".\" />")
				.append("<default revision=\"master\" remote=\"remote1\" />")
				.append("<project path=\"foo\" name=\"foo\"")
				.append("  dest-branch=\"branchX\"/>")
				.append("<project path=\"bar\" name=\"bar\"/>")
				.append("</manifest>");

		ManifestParser parser = new ManifestParser(null, null, "master",
				"https://git.google.com/", null, null);
		parser.read(new ByteArrayInputStream(
				xmlContent.toString().getBytes(UTF_8)));

		Map<String, RepoProject> repos = parser.getProjects().stream().collect(
				Collectors.toMap(RepoProject::getName, Function.identity()));
		assertEquals(2, repos.size());

		RepoProject foo = repos.get("foo");
		assertEquals("foo", foo.getName());
		assertEquals("branchX", foo.getDestBranch());

		RepoProject bar = repos.get("bar");
		assertEquals("bar", bar.getName());
		assertNull(bar.getDestBranch());
	}

	void testNormalize(String in, String want) {
		URI got = ManifestParser.normalizeEmptyPath(URI.create(in));
		if (!got.toString().equals(want)) {
			fail(String.format("normalize(%s) = %s want %s", in, got, want));
		}
	}

	@Test
	public void testNormalizeEmptyPath() {
		testNormalize("http://a.b", "http://a.b/");
		testNormalize("http://a.b/", "http://a.b/");
		testNormalize("", "");
		testNormalize("a/b", "a/b");
	}

	@Test
	public void testXXE() throws Exception {
		File externalEntity = File.createTempFile("injected", "xml");
		externalEntity.deleteOnExit();
		Files.write(externalEntity.toPath(),
				"<evil>injected xml</evil>"
						.getBytes(UTF_8),
				StandardOpenOption.WRITE);
		String baseUrl = "https://git.google.com/";
		StringBuilder xmlContent = new StringBuilder();
		xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<!DOCTYPE booo [ <!ENTITY foobar SYSTEM \"")
				.append(externalEntity.getPath()).append("\"> ]>\n")
				.append("<manifest>")
				.append("<remote name=\"remote1\" fetch=\".\" />")
				.append("<default revision=\"master\" remote=\"remote1\" />")
				.append("&foobar;")
				.append("<project path=\"foo\" name=\"foo\" groups=\"a,test\" />")
				.append("</manifest>");

		IOException e = assertThrows(IOException.class,
				() -> new ManifestParser(null, null, "master", baseUrl, null,
						null)
						.read(new ByteArrayInputStream(
								xmlContent.toString().getBytes(UTF_8))));
		assertTrue(e.getCause().getMessage().contains("DOCTYPE"));
	}

}
