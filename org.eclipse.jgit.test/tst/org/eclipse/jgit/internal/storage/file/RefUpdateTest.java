/*
 * Copyright (C) 2008, Charles O'Farrell <charleso@charleso.org>
 * Copyright (C) 2009-2010, Google Inc.
 * Copyright (C) 2008-2013, Robin Rosenberg <robin.rosenberg@dewire.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.junit.Assert.assertEquals;
import static org.eclipse.jgit.lib.Constants.LOCK_SUFFIX;
import static org.eclipse.jgit.lib.RefUpdate.Result.FORCED;
import static org.eclipse.jgit.lib.RefUpdate.Result.IO_FAILURE;
import static org.eclipse.jgit.lib.RefUpdate.Result.LOCK_FAILURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefRename;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.test.resources.SampleDataRepositoryTestCase;
import org.junit.Test;

public class RefUpdateTest extends SampleDataRepositoryTestCase {
	private void writeSymref(String src, String dst) throws IOException {
		RefUpdate u = db.updateRef(src);
		switch (u.link(dst)) {
		case NEW:
		case FORCED:
		case NO_CHANGE:
			break;
		default:
			fail("link " + src + " to " + dst);
		}
	}

	private RefUpdate updateRef(String name) throws IOException {
		final RefUpdate ref = db.updateRef(name);
		ref.setNewObjectId(db.resolve(Constants.HEAD));
		return ref;
	}

	private void delete(RefUpdate ref, Result expected)
			throws IOException {
		delete(ref, expected, true, true);
	}

	private void delete(final RefUpdate ref, final Result expected,
			final boolean exists, final boolean removed) throws IOException {
		delete(db, ref, expected, exists, removed);
	}

	private void delete(Repository repo, final RefUpdate ref,
			final Result expected, final boolean exists, final boolean removed)
			throws IOException {
		assertEquals(exists, getRef(repo, ref.getName()).isPresent());
		assertEquals(expected, ref.delete());
		assertEquals(!removed, getRef(repo, ref.getName()).isPresent());
	}

	private Optional<Ref> getRef(Repository repo, String name)
			throws IOException {
		return getRef(repo.getRefDatabase().getRefs(), name);
	}

	private Optional<Ref> getRef(List<Ref> refs, String name) {
		return refs.stream().filter(r -> r.getName().equals(name)).findAny();
	}

	@Test
	public void testNoCacheObjectIdSubclass() throws IOException {
		final String newRef = "refs/heads/abc";
		final RefUpdate ru = updateRef(newRef);
		final SubclassedId newid = new SubclassedId(ru.getNewObjectId());
		ru.setNewObjectId(newid);
		Result update = ru.update();
		assertEquals(Result.NEW, update);
		final Ref r = getRef(db, newRef).get();
		assertEquals(newRef, r.getName());
		assertNotNull(r.getObjectId());
		assertNotSame(newid, r.getObjectId());
		assertSame(ObjectId.class, r.getObjectId().getClass());
		assertEquals(newid, r.getObjectId());
		List<ReflogEntry> reverseEntries1 = db.getRefDatabase()
				.getReflogReader("refs/heads/abc").getReverseEntries();
		ReflogEntry entry1 = reverseEntries1.get(0);
		assertEquals(1, reverseEntries1.size());
		assertEquals(ObjectId.zeroId(), entry1.getOldId());
		assertEquals(r.getObjectId(), entry1.getNewId());
		assertEquals(new PersonIdent(db).toString(),
				entry1.getWho().toString());
		assertEquals("", entry1.getComment());
		List<ReflogEntry> reverseEntries2 = db.getRefDatabase()
				.getReflogReader("HEAD").getReverseEntries();
		assertEquals(0, reverseEntries2.size());
	}

	@Test
	public void testNewNamespaceConflictWithLoosePrefixNameExists()
			throws IOException {
		final String newRef = "refs/heads/z";
		final RefUpdate ru = updateRef(newRef);
		Result update = ru.update();
		assertEquals(Result.NEW, update);
		// end setup
		final String newRef2 = "refs/heads/z/a";
		final RefUpdate ru2 = updateRef(newRef2);
		Result update2 = ru2.update();
		assertEquals(Result.LOCK_FAILURE, update2);
		RefDatabase refDb = db.getRefDatabase();
		assertEquals(1, refDb.getReflogReader("refs/heads/z")
				.getReverseEntries().size());
		assertEquals(0,
				refDb.getReflogReader("HEAD").getReverseEntries().size());
	}

	@Test
	public void testNewNamespaceConflictWithPackedPrefixNameExists()
			throws IOException {
		final String newRef = "refs/heads/master/x";
		final RefUpdate ru = updateRef(newRef);
		Result update = ru.update();
		assertEquals(Result.LOCK_FAILURE, update);
		RefDatabase refDb = db.getRefDatabase();
		assertNull(refDb.getReflogReader("refs/heads/master/x"));
		assertEquals(0,
				refDb.getReflogReader("HEAD").getReverseEntries().size());
	}

	@Test
	public void testNewNamespaceConflictWithLoosePrefixOfExisting()
			throws IOException {
		final String newRef = "refs/heads/z/a";
		final RefUpdate ru = updateRef(newRef);
		Result update = ru.update();
		assertEquals(Result.NEW, update);
		// end setup
		final String newRef2 = "refs/heads/z";
		final RefUpdate ru2 = updateRef(newRef2);
		Result update2 = ru2.update();
		assertEquals(Result.LOCK_FAILURE, update2);
		RefDatabase refDb = db.getRefDatabase();
		assertEquals(1, refDb.getReflogReader("refs/heads/z/a")
				.getReverseEntries().size());
		assertNull(refDb.getReflogReader("refs/heads/z"));
		assertEquals(0,
				refDb.getReflogReader("HEAD").getReverseEntries().size());
	}

	@Test
	public void testNewNamespaceConflictWithPackedPrefixOfExisting()
			throws IOException {
		final String newRef = "refs/heads/prefix";
		final RefUpdate ru = updateRef(newRef);
		Result update = ru.update();
		assertEquals(Result.LOCK_FAILURE, update);
		RefDatabase refDb = db.getRefDatabase();
		assertNull(refDb.getReflogReader("refs/heads/prefix"));
		assertEquals(0,
				refDb.getReflogReader("HEAD").getReverseEntries().size());
	}

	/**
	 * Delete a ref that is pointed to by HEAD
	 *
	 * @throws IOException
	 */
	@Test
	public void testDeleteHEADreferencedRef() throws IOException {
		ObjectId pid = db.resolve("refs/heads/master^");
		RefUpdate updateRef = db.updateRef("refs/heads/master");
		updateRef.setNewObjectId(pid);
		updateRef.setForceUpdate(true);
		Result update = updateRef.update();
		assertEquals(Result.FORCED, update); // internal

		RefUpdate updateRef2 = db.updateRef("refs/heads/master");
		Result delete = updateRef2.delete();
		assertEquals(Result.REJECTED_CURRENT_BRANCH, delete);
		assertEquals(pid, db.resolve("refs/heads/master"));
		RefDatabase refDb = db.getRefDatabase();
		assertEquals(1, refDb.getReflogReader("refs/heads/master")
				.getReverseEntries().size());
		assertEquals(0,
				refDb.getReflogReader("HEAD").getReverseEntries().size());
	}

	@Test
	public void testWriteReflog() throws IOException {
		ObjectId pid = db.resolve("refs/heads/master^");
		RefUpdate updateRef = db.updateRef("refs/heads/master");
		updateRef.setNewObjectId(pid);
		updateRef.setForceUpdate(true);
		Result update = updateRef.update();
		assertEquals(Result.FORCED, update);
		assertEquals(1, db.getRefDatabase().getReflogReader("refs/heads/master")
				.getReverseEntries().size());
	}

	@Test
	public void testLooseDelete() throws IOException {
		final String newRef = "refs/heads/abc";
		RefUpdate ref = updateRef(newRef);
		ref.update(); // create loose ref
		ref = updateRef(newRef); // refresh
		delete(ref, Result.NO_CHANGE);
		assertNull(db.getRefDatabase().getReflogReader("refs/heads/abc"));
	}

	@Test
	public void testDeleteHead() throws IOException {
		final RefUpdate ref = updateRef(Constants.HEAD);
		delete(ref, Result.REJECTED_CURRENT_BRANCH, true, false);
		RefDatabase refDb = db.getRefDatabase();
		assertEquals(0, refDb.getReflogReader("refs/heads/master")
				.getReverseEntries().size());
		assertEquals(0,
				refDb.getReflogReader("HEAD").getReverseEntries().size());
	}

	@Test
	public void testDeleteHeadInBareRepo() throws IOException {
		Repository bareRepo = createBareRepository();
		String master = "refs/heads/master";
		Ref head = bareRepo.exactRef(Constants.HEAD);
		assertNotNull(head);
		assertTrue(head.isSymbolic());
		assertEquals(master, head.getLeaf().getName());
		assertNull(head.getObjectId());
		assertNull(bareRepo.exactRef(master));

		ObjectId blobId;
		try (ObjectInserter ins = bareRepo.newObjectInserter()) {
			blobId = ins.insert(Constants.OBJ_BLOB, "contents".getBytes(UTF_8));
			ins.flush();
		}

		// Create master via HEAD, so we delete it.
		RefUpdate ref = bareRepo.updateRef(Constants.HEAD);
		ref.setNewObjectId(blobId);
		assertEquals(Result.NEW, ref.update());

		head = bareRepo.exactRef(Constants.HEAD);
		assertTrue(head.isSymbolic());
		assertEquals(master, head.getLeaf().getName());
		assertEquals(blobId, head.getLeaf().getObjectId());
		assertEquals(blobId, bareRepo.exactRef(master).getObjectId());

		// Unlike in a non-bare repo, deleting the HEAD is allowed, and leaves HEAD
		// back in a dangling state.
		ref = bareRepo.updateRef(Constants.HEAD);
		ref.setExpectedOldObjectId(blobId);
		ref.setForceUpdate(true);
		delete(bareRepo, ref, Result.FORCED, true, true);

		head = bareRepo.exactRef(Constants.HEAD);
		assertNotNull(head);
		assertTrue(head.isSymbolic());
		assertEquals(master, head.getLeaf().getName());
		assertNull(head.getObjectId());
		assertNull(bareRepo.exactRef(master));
	}

	@Test
	public void testDeleteSymref() throws IOException {
		RefUpdate dst = updateRef("refs/heads/abc");
		assertEquals(Result.NEW, dst.update());
		ObjectId id = dst.getNewObjectId();

		RefUpdate u = db.updateRef("refs/symref");
		assertEquals(Result.NEW, u.link(dst.getName()));

		Ref ref = db.exactRef(u.getName());
		assertNotNull(ref);
		assertTrue(ref.isSymbolic());
		assertEquals(dst.getName(), ref.getLeaf().getName());
		assertEquals(id, ref.getLeaf().getObjectId());

		u = db.updateRef(u.getName());
		u.setDetachingSymbolicRef();
		u.setForceUpdate(true);
		assertEquals(Result.FORCED, u.delete());

		assertNull(db.exactRef(u.getName()));
		ref = db.exactRef(dst.getName());
		assertNotNull(ref);
		assertFalse(ref.isSymbolic());
		assertEquals(id, ref.getObjectId());
	}

	/**
	 * Delete a loose ref and make sure the directory in refs is deleted too,
	 * and the reflog dir too
	 *
	 * @throws IOException
	 */
	@Test
	public void testDeleteLooseAndItsDirectory() throws IOException {
		ObjectId pid = db.resolve("refs/heads/c^");
		RefUpdate updateRef = db.updateRef("refs/heads/z/c");
		updateRef.setNewObjectId(pid);
		updateRef.setForceUpdate(true);
		updateRef.setRefLogMessage("new test ref", false);
		Result update = updateRef.update();
		assertEquals(Result.NEW, update); // internal
		assertTrue(new File(db.getDirectory(), Constants.R_HEADS + "z")
				.exists());
		assertTrue(new File(db.getDirectory(), "logs/refs/heads/z").exists());

		// The real test here
		RefUpdate updateRef2 = db.updateRef("refs/heads/z/c");
		updateRef2.setForceUpdate(true);
		Result delete = updateRef2.delete();
		assertEquals(Result.FORCED, delete);
		assertNull(db.resolve("refs/heads/z/c"));
		assertFalse(new File(db.getDirectory(), Constants.R_HEADS + "z")
				.exists());
		assertFalse(new File(db.getDirectory(), "logs/refs/heads/z").exists());
	}

	@Test
	public void testDeleteNotFound() throws IOException {
		final RefUpdate ref = updateRef("refs/heads/xyz");
		delete(ref, Result.NEW, false, true);
	}

	@Test
	public void testDeleteFastForward() throws IOException {
		final RefUpdate ref = updateRef("refs/heads/a");
		delete(ref, Result.FAST_FORWARD);
	}

	@Test
	public void testDeleteForce() throws IOException {
		final RefUpdate ref = db.updateRef("refs/heads/b");
		ref.setNewObjectId(db.resolve("refs/heads/a"));
		delete(ref, Result.REJECTED, true, false);
		ref.setForceUpdate(true);
		delete(ref, Result.FORCED);
	}

	@Test
	public void testDeleteWithoutHead() throws IOException {
		// Prepare repository without HEAD
		RefUpdate refUpdate = db.updateRef(Constants.HEAD, true);
		refUpdate.setForceUpdate(true);
		refUpdate.setNewObjectId(ObjectId.zeroId());
		Result updateResult = refUpdate.update();
		assertEquals(Result.FORCED, updateResult);

		assertEquals(ObjectId.zeroId(), db.exactRef("HEAD").getObjectId());
		Result deleteHeadResult = db.updateRef(Constants.HEAD).delete();
		assertEquals(Result.NO_CHANGE, deleteHeadResult);

		// Any result is ok as long as it's not an NPE
		db.updateRef(Constants.R_HEADS + "master").delete();
	}

	@Test
	public void testRefKeySameAsName() {
		@SuppressWarnings("deprecation")
		Map<String, Ref> allRefs = db.getAllRefs();
		for (Entry<String, Ref> e : allRefs.entrySet()) {
			assertEquals(e.getKey(), e.getValue().getName());
		}
	}

	/**
	 * Try modify a ref forward, fast forward
	 *
	 * @throws IOException
	 */
	@Test
	public void testUpdateRefForward() throws IOException {
		ObjectId ppid = db.resolve("refs/heads/master^");
		ObjectId pid = db.resolve("refs/heads/master");

		RefUpdate updateRef = db.updateRef("refs/heads/master");
		updateRef.setNewObjectId(ppid);
		updateRef.setForceUpdate(true);
		Result update = updateRef.update();
		assertEquals(Result.FORCED, update);
		assertEquals(ppid, db.resolve("refs/heads/master"));

		// real test
		RefUpdate updateRef2 = db.updateRef("refs/heads/master");
		updateRef2.setNewObjectId(pid);
		Result update2 = updateRef2.update();
		assertEquals(Result.FAST_FORWARD, update2);
		assertEquals(pid, db.resolve("refs/heads/master"));
	}

	/**
	 * Update the HEAD ref. Only it should be changed, not what it points to.
	 *
	 * @throws Exception
	 */
	@Test
	public void testUpdateRefDetached() throws Exception {
		ObjectId pid = db.resolve("refs/heads/master");
		ObjectId ppid = db.resolve("refs/heads/master^");
		RefUpdate updateRef = db.updateRef("HEAD", true);
		updateRef.setForceUpdate(true);
		updateRef.setNewObjectId(ppid);
		Result update = updateRef.update();
		assertEquals(Result.FORCED, update);
		assertEquals(ppid, db.resolve("HEAD"));
		Ref ref = db.exactRef("HEAD");
		assertEquals("HEAD", ref.getName());
		assertTrue("is detached", !ref.isSymbolic());

		// the branch HEAD referred to is left untouched
		assertEquals(pid, db.resolve("refs/heads/master"));
		ReflogReader reflogReader = db.getRefDatabase().getReflogReader("HEAD");
		ReflogEntry e = reflogReader.getReverseEntries().get(0);
		assertEquals(pid, e.getOldId());
		assertEquals(ppid, e.getNewId());
		assertEquals("GIT_COMMITTER_EMAIL", e.getWho().getEmailAddress());
		assertEquals("GIT_COMMITTER_NAME", e.getWho().getName());
		assertEquals(1250379778000L, e.getWho().getWhen().getTime());
	}

	/**
	 * Update the HEAD ref when the referenced branch is unborn
	 *
	 * @throws Exception
	 */
	@Test
	public void testUpdateRefDetachedUnbornHead() throws Exception {
		ObjectId ppid = db.resolve("refs/heads/master^");
		writeSymref("HEAD", "refs/heads/unborn");
		RefUpdate updateRef = db.updateRef("HEAD", true);
		updateRef.setForceUpdate(true);
		updateRef.setNewObjectId(ppid);
		Result update = updateRef.update();
		assertEquals(Result.NEW, update);
		assertEquals(ppid, db.resolve("HEAD"));
		Ref ref = db.exactRef("HEAD");
		assertEquals("HEAD", ref.getName());
		assertTrue("is detached", !ref.isSymbolic());

		// the branch HEAD referred to is left untouched
		assertNull(db.resolve("refs/heads/unborn"));
		ReflogReader reflogReader = db.getRefDatabase().getReflogReader("HEAD");
		ReflogEntry e = reflogReader.getReverseEntries().get(0);
		assertEquals(ObjectId.zeroId(), e.getOldId());
		assertEquals(ppid, e.getNewId());
		assertEquals("GIT_COMMITTER_EMAIL", e.getWho().getEmailAddress());
		assertEquals("GIT_COMMITTER_NAME", e.getWho().getName());
		assertEquals(1250379778000L, e.getWho().getWhen().getTime());
	}

	/**
	 * Delete a ref that exists both as packed and loose. Make sure the ref
	 * cannot be resolved after delete.
	 *
	 * @throws IOException
	 */
	@Test
	public void testDeleteLoosePacked() throws IOException {
		ObjectId pid = db.resolve("refs/heads/c^");
		RefUpdate updateRef = db.updateRef("refs/heads/c");
		updateRef.setNewObjectId(pid);
		updateRef.setForceUpdate(true);
		Result update = updateRef.update();
		assertEquals(Result.FORCED, update); // internal

		// The real test here
		RefUpdate updateRef2 = db.updateRef("refs/heads/c");
		updateRef2.setForceUpdate(true);
		Result delete = updateRef2.delete();
		assertEquals(Result.FORCED, delete);
		assertNull(db.resolve("refs/heads/c"));
	}

	/**
	 * Try modify a ref to same
	 *
	 * @throws IOException
	 */
	@Test
	public void testUpdateRefNoChange() throws IOException {
		ObjectId pid = db.resolve("refs/heads/master");
		RefUpdate updateRef = db.updateRef("refs/heads/master");
		updateRef.setNewObjectId(pid);
		Result update = updateRef.update();
		assertEquals(Result.NO_CHANGE, update);
		assertEquals(pid, db.resolve("refs/heads/master"));
	}

	/**
	 * Test case originating from
	 * <a href="http://bugs.eclipse.org/285991">bug 285991</a>
	 *
	 * Make sure the in memory cache is updated properly after
	 * update of symref. This one did not fail because the
	 * ref was packed due to implementation issues.
	 *
	 * @throws Exception
	 */
	@Test
	public void testRefsCacheAfterUpdate() throws Exception {
		// Do not use the default repo for this case.
		ObjectId oldValue = db.resolve("HEAD");
		ObjectId newValue = db.resolve("HEAD^");
		// first make HEAD refer to loose ref
		RefUpdate updateRef = db.updateRef(Constants.HEAD);
		updateRef.setForceUpdate(true);
		updateRef.setNewObjectId(newValue);
		Result update = updateRef.update();
		assertEquals(Result.FORCED, update);

		// now update that ref
		updateRef = db.updateRef(Constants.HEAD);
		updateRef.setNewObjectId(oldValue);
		update = updateRef.update();
		assertEquals(Result.FAST_FORWARD, update);

		List<Ref> allRefs = db.getRefDatabase().getRefs();
		Ref master = getRef(allRefs, "refs/heads/master").get();
		Ref head = getRef(allRefs, "HEAD").get();
		assertEquals("refs/heads/master", master.getName());
		assertEquals("HEAD", head.getName());
		assertTrue("is symbolic reference", head.isSymbolic());
		assertSame(master, head.getTarget());
	}

	/**
	 * Test case originating from
	 * <a href="http://bugs.eclipse.org/285991">bug 285991</a>
	 *
	 * Make sure the in memory cache is updated properly after
	 * update of symref.
	 *
	 * @throws Exception
	 */
	@Test
	public void testRefsCacheAfterUpdateLooseOnly() throws Exception {
		// Do not use the default repo for this case.
		ObjectId oldValue = db.resolve("HEAD");
		writeSymref(Constants.HEAD, "refs/heads/newref");
		RefUpdate updateRef = db.updateRef(Constants.HEAD);
		updateRef.setForceUpdate(true);
		updateRef.setNewObjectId(oldValue);
		Result update = updateRef.update();
		assertEquals(Result.NEW, update);

		List<Ref> allRefs = db.getRefDatabase().getRefs();
		Ref head = getRef(allRefs, "HEAD").get();
		Ref newref = getRef(allRefs, "refs/heads/newref").get();
		assertEquals("refs/heads/newref", newref.getName());
		assertEquals("HEAD", head.getName());
		assertTrue("is symbolic reference", head.isSymbolic());
		assertSame(newref, head.getTarget());
	}

	/**
	 * Try modify a ref, but get wrong expected old value
	 *
	 * @throws IOException
	 */
	@Test
	public void testUpdateRefLockFailureWrongOldValue() throws IOException {
		ObjectId pid = db.resolve("refs/heads/master");
		RefUpdate updateRef = db.updateRef("refs/heads/master");
		updateRef.setNewObjectId(pid);
		updateRef.setExpectedOldObjectId(db.resolve("refs/heads/master^"));
		Result update = updateRef.update();
		assertEquals(Result.LOCK_FAILURE, update);
		assertEquals(pid, db.resolve("refs/heads/master"));
	}

	/**
	 * Try modify a ref forward, fast forward, checking old value first
	 *
	 * @throws IOException
	 */
	@Test
	public void testUpdateRefForwardWithCheck1() throws IOException {
		ObjectId ppid = db.resolve("refs/heads/master^");
		ObjectId pid = db.resolve("refs/heads/master");

		RefUpdate updateRef = db.updateRef("refs/heads/master");
		updateRef.setNewObjectId(ppid);
		updateRef.setForceUpdate(true);
		Result update = updateRef.update();
		assertEquals(Result.FORCED, update);
		assertEquals(ppid, db.resolve("refs/heads/master"));

		// real test
		RefUpdate updateRef2 = db.updateRef("refs/heads/master");
		updateRef2.setExpectedOldObjectId(ppid);
		updateRef2.setNewObjectId(pid);
		Result update2 = updateRef2.update();
		assertEquals(Result.FAST_FORWARD, update2);
		assertEquals(pid, db.resolve("refs/heads/master"));
	}

	/**
	 * Try modify a ref forward, fast forward, checking old commit first
	 *
	 * @throws IOException
	 */
	@Test
	public void testUpdateRefForwardWithCheck2() throws IOException {
		ObjectId ppid = db.resolve("refs/heads/master^");
		ObjectId pid = db.resolve("refs/heads/master");

		RefUpdate updateRef = db.updateRef("refs/heads/master");
		updateRef.setNewObjectId(ppid);
		updateRef.setForceUpdate(true);
		Result update = updateRef.update();
		assertEquals(Result.FORCED, update);
		assertEquals(ppid, db.resolve("refs/heads/master"));

		// real test
		try (RevWalk rw = new RevWalk(db)) {
			RevCommit old = rw.parseCommit(ppid);
			RefUpdate updateRef2 = db.updateRef("refs/heads/master");
			updateRef2.setExpectedOldObjectId(old);
			updateRef2.setNewObjectId(pid);
			Result update2 = updateRef2.update();
			assertEquals(Result.FAST_FORWARD, update2);
			assertEquals(pid, db.resolve("refs/heads/master"));
		}
	}

	/**
	 * Try modify a ref that is locked
	 *
	 * @throws IOException
	 */
	@Test
	public void testUpdateRefLockFailureLocked() throws IOException {
		ObjectId opid = db.resolve("refs/heads/master");
		ObjectId pid = db.resolve("refs/heads/master^");
		RefUpdate updateRef = db.updateRef("refs/heads/master");
		updateRef.setNewObjectId(pid);
		LockFile lockFile1 = new LockFile(new File(db.getDirectory(),
				"refs/heads/master"));
		try {
			assertTrue(lockFile1.lock()); // precondition to test
			Result update = updateRef.update();
			assertEquals(Result.LOCK_FAILURE, update);
			assertEquals(opid, db.resolve("refs/heads/master"));
			LockFile lockFile2 = new LockFile(new File(db.getDirectory(),"refs/heads/master"));
			assertFalse(lockFile2.lock()); // was locked, still is
		} finally {
			lockFile1.unlock();
		}
	}

	/**
	 * Try to delete a ref. Delete requires force.
	 *
	 * @throws IOException
	 */
	@Test
	public void testDeleteLoosePackedRejected() throws IOException {
		ObjectId pid = db.resolve("refs/heads/c^");
		ObjectId oldpid = db.resolve("refs/heads/c");
		RefUpdate updateRef = db.updateRef("refs/heads/c");
		updateRef.setNewObjectId(pid);
		Result update = updateRef.update();
		assertEquals(Result.REJECTED, update);
		assertEquals(oldpid, db.resolve("refs/heads/c"));
	}

	@Test
	public void testRenameBranchNoPreviousLog() throws IOException {
		assertFalse("precondition, no log on old branchg", new File(db
				.getDirectory(), "logs/refs/heads/b").exists());
		ObjectId rb = db.resolve("refs/heads/b");
		ObjectId oldHead = db.resolve(Constants.HEAD);
		assertFalse(rb.equals(oldHead)); // assumption for this test
		RefRename renameRef = db.renameRef("refs/heads/b",
				"refs/heads/new/name");
		Result result = renameRef.rename();
		assertEquals(Result.RENAMED, result);
		assertEquals(rb, db.resolve("refs/heads/new/name"));
		assertNull(db.resolve("refs/heads/b"));
		RefDatabase refDb = db.getRefDatabase();
		assertEquals(1, refDb.getReflogReader("refs/heads/new/name")
				.getReverseEntries().size());
		assertEquals("Branch: renamed b to new/name",
				refDb.getReflogReader("refs/heads/new/name").getLastEntry()
						.getComment());
		assertFalse(new File(db.getDirectory(), "logs/refs/heads/b").exists());
		assertEquals(oldHead, db.resolve(Constants.HEAD)); // unchanged
	}

	@Test
	public void testRenameBranchHasPreviousLog() throws IOException {
		ObjectId rb = db.resolve("refs/heads/b");
		ObjectId oldHead = db.resolve(Constants.HEAD);
		assertFalse("precondition for this test, branch b != HEAD", rb
				.equals(oldHead));
		writeReflog(db, rb, "Just a message", "refs/heads/b");
		assertTrue("log on old branch", new File(db.getDirectory(),
				"logs/refs/heads/b").exists());
		RefRename renameRef = db.renameRef("refs/heads/b",
				"refs/heads/new/name");
		Result result = renameRef.rename();
		assertEquals(Result.RENAMED, result);
		assertEquals(rb, db.resolve("refs/heads/new/name"));
		assertNull(db.resolve("refs/heads/b"));
		RefDatabase refDb = db.getRefDatabase();
		assertEquals(2, refDb.getReflogReader("refs/heads/new/name")
				.getReverseEntries().size());
		assertEquals("Branch: renamed b to new/name",
				refDb.getReflogReader("refs/heads/new/name").getLastEntry()
						.getComment());
		assertEquals("Just a message",
				refDb.getReflogReader("refs/heads/new/name").getReverseEntries()
						.get(1).getComment());
		assertFalse(new File(db.getDirectory(), "logs/refs/heads/b").exists());
		assertEquals(oldHead, db.resolve(Constants.HEAD)); // unchanged
	}

	@Test
	public void testRenameCurrentBranch() throws IOException {
		ObjectId rb = db.resolve("refs/heads/b");
		writeSymref(Constants.HEAD, "refs/heads/b");
		ObjectId oldHead = db.resolve(Constants.HEAD);
		assertEquals("internal test condition, b == HEAD", oldHead, rb);
		writeReflog(db, rb, "Just a message", "refs/heads/b");
		assertTrue("log on old branch", new File(db.getDirectory(),
				"logs/refs/heads/b").exists());
		RefRename renameRef = db.renameRef("refs/heads/b",
				"refs/heads/new/name");
		Result result = renameRef.rename();
		assertEquals(Result.RENAMED, result);
		assertEquals(rb, db.resolve("refs/heads/new/name"));
		assertNull(db.resolve("refs/heads/b"));
		RefDatabase refDb = db.getRefDatabase();
		assertEquals("Branch: renamed b to new/name",
				refDb.getReflogReader("refs/heads/new/name").getLastEntry()
						.getComment());
		assertFalse(new File(db.getDirectory(), "logs/refs/heads/b").exists());
		assertEquals(rb, db.resolve(Constants.HEAD));
		assertEquals(2, refDb.getReflogReader("refs/heads/new/name")
				.getReverseEntries().size());
		assertEquals("Branch: renamed b to new/name",
				refDb.getReflogReader("refs/heads/new/name").getReverseEntries()
						.get(0).getComment());
		assertEquals("Just a message",
				refDb.getReflogReader("refs/heads/new/name").getReverseEntries()
						.get(1).getComment());
	}

	@Test
	public void testRenameBranchAlsoInPack() throws IOException {
		ObjectId rb = db.resolve("refs/heads/b");
		ObjectId rb2 = db.resolve("refs/heads/b~1");
		assertEquals(Ref.Storage.PACKED, db.exactRef("refs/heads/b").getStorage());
		RefUpdate updateRef = db.updateRef("refs/heads/b");
		updateRef.setNewObjectId(rb2);
		updateRef.setForceUpdate(true);
		Result update = updateRef.update();
		assertEquals("internal check new ref is loose", Result.FORCED, update);
		assertEquals(Ref.Storage.LOOSE, db.exactRef("refs/heads/b").getStorage());
		writeReflog(db, rb, "Just a message", "refs/heads/b");
		assertTrue("log on old branch", new File(db.getDirectory(),
				"logs/refs/heads/b").exists());
		RefRename renameRef = db.renameRef("refs/heads/b",
				"refs/heads/new/name");
		Result result = renameRef.rename();
		assertEquals(Result.RENAMED, result);
		assertEquals(rb2, db.resolve("refs/heads/new/name"));
		assertNull(db.resolve("refs/heads/b"));
		RefDatabase refDb = db.getRefDatabase();
		assertEquals("Branch: renamed b to new/name",
				refDb.getReflogReader("refs/heads/new/name").getLastEntry()
						.getComment());
		assertEquals(3, refDb.getReflogReader("refs/heads/new/name")
				.getReverseEntries().size());
		assertEquals("Branch: renamed b to new/name",
				refDb.getReflogReader("refs/heads/new/name").getReverseEntries()
						.get(0).getComment());
		assertEquals(0,
				refDb.getReflogReader("HEAD").getReverseEntries().size());
		// make sure b's log file is gone too.
		assertFalse(new File(db.getDirectory(), "logs/refs/heads/b").exists());

		// Create new Repository instance, to reread caches and make sure our
		// assumptions are persistent.
		try (Repository ndb = new FileRepository(db.getDirectory())) {
			assertEquals(rb2, ndb.resolve("refs/heads/new/name"));
			assertNull(ndb.resolve("refs/heads/b"));
		}
	}

	public void tryRenameWhenLocked(String toLock, String fromName,
			String toName, String headPointsTo) throws IOException {
		// setup
		writeSymref(Constants.HEAD, headPointsTo);
		ObjectId oldfromId = db.resolve(fromName);
		ObjectId oldHeadId = db.resolve(Constants.HEAD);
		writeReflog(db, oldfromId, "Just a message", fromName);
		RefDatabase refDb = db.getRefDatabase();
		List<ReflogEntry> oldFromLog = refDb
				.getReflogReader(fromName).getReverseEntries();
		List<ReflogEntry> oldHeadLog = oldHeadId != null ? refDb
				.getReflogReader(Constants.HEAD).getReverseEntries() : null;

		assertTrue("internal check, we have a log", new File(db.getDirectory(),
				"logs/" + fromName).exists());

		// "someone" has branch X locked
		LockFile lockFile = new LockFile(new File(db.getDirectory(), toLock));
		try {
			assertTrue(lockFile.lock());

			// Now this is our test
			RefRename renameRef = db.renameRef(fromName, toName);
			Result result = renameRef.rename();
			assertEquals(Result.LOCK_FAILURE, result);

			// Check that the involved refs are the same despite the failure
			assertExists(false, toName);
			if (!toLock.equals(toName))
				assertExists(false, toName + LOCK_SUFFIX);
			assertExists(true, toLock + LOCK_SUFFIX);
			if (!toLock.equals(fromName))
				assertExists(false, "logs/" + fromName + LOCK_SUFFIX);
			assertExists(false, "logs/" + toName + LOCK_SUFFIX);
			assertEquals(oldHeadId, db.resolve(Constants.HEAD));
			assertEquals(oldfromId, db.resolve(fromName));
			assertNull(db.resolve(toName));
			assertEquals(oldFromLog.toString(), refDb.getReflogReader(fromName)
					.getReverseEntries().toString());
			if (oldHeadId != null && oldHeadLog != null)
				assertEquals(oldHeadLog.toString(), refDb.getReflogReader(
						Constants.HEAD).getReverseEntries().toString());
		} finally {
			lockFile.unlock();
		}
	}

	private void assertExists(boolean positive, String toName) {
		assertEquals(toName + (positive ? " " : " does not ") + "exist",
				positive, new File(db.getDirectory(), toName).exists());
	}

	@Test
	public void testRenameBranchCannotLockAFileHEADisFromLockHEAD()
			throws IOException {
		tryRenameWhenLocked("HEAD", "refs/heads/b", "refs/heads/new/name",
				"refs/heads/b");
	}

	@Test
	public void testRenameBranchCannotLockAFileHEADisFromLockFrom()
			throws IOException {
		tryRenameWhenLocked("refs/heads/b", "refs/heads/b",
				"refs/heads/new/name", "refs/heads/b");
	}

	@Test
	public void testRenameBranchCannotLockAFileHEADisFromLockTo()
			throws IOException {
		tryRenameWhenLocked("refs/heads/new/name", "refs/heads/b",
				"refs/heads/new/name", "refs/heads/b");
	}

	@Test
	public void testRenameBranchCannotLockAFileHEADisToLockFrom()
			throws IOException {
		tryRenameWhenLocked("refs/heads/b", "refs/heads/b",
				"refs/heads/new/name", "refs/heads/new/name");
	}

	@Test
	public void testRenameBranchCannotLockAFileHEADisToLockTo()
			throws IOException {
		tryRenameWhenLocked("refs/heads/new/name", "refs/heads/b",
				"refs/heads/new/name", "refs/heads/new/name");
	}

	@Test
	public void testRenameBranchCannotLockAFileHEADisOtherLockFrom()
			throws IOException {
		tryRenameWhenLocked("refs/heads/b", "refs/heads/b",
				"refs/heads/new/name", "refs/heads/a");
	}

	@Test
	public void testRenameBranchCannotLockAFileHEADisOtherLockTo()
			throws IOException {
		tryRenameWhenLocked("refs/heads/new/name", "refs/heads/b",
				"refs/heads/new/name", "refs/heads/a");
	}

	@Test
	public void testUpdateChecksOldValue() throws Exception {
		ObjectId cur = db.resolve("master");
		ObjectId prev = db.resolve("master^");
		RefUpdate u1 = db.updateRef("refs/heads/master");
		RefUpdate u2 = db.updateRef("refs/heads/master");

		u1.setExpectedOldObjectId(cur);
		u1.setNewObjectId(prev);
		u1.setForceUpdate(true);

		u2.setExpectedOldObjectId(cur);
		u2.setNewObjectId(prev);
		u2.setForceUpdate(true);

		assertEquals(FORCED, u1.update());
		assertEquals(LOCK_FAILURE, u2.update());
	}

	@Test
	public void testRenameAtomic() throws IOException {
		ObjectId prevId = db.resolve("refs/heads/master^");

		RefRename rename = db.renameRef("refs/heads/master", "refs/heads/newmaster");

		RefUpdate updateRef = db.updateRef("refs/heads/master");
		updateRef.setNewObjectId(prevId);
		updateRef.setForceUpdate(true);
		assertEquals(FORCED, updateRef.update());
		assertEquals(RefUpdate.Result.LOCK_FAILURE, rename.rename());
	}

	@Test
	public void testRenameSymref() throws IOException {
		db.resolve("HEAD");
		RefRename r = db.renameRef("HEAD", "KOPF");
		assertEquals(IO_FAILURE, r.rename());
	}

	@Test
	public void testRenameRefNameColission1avoided() throws IOException {
		// setup
		ObjectId rb = db.resolve("refs/heads/b");
		writeSymref(Constants.HEAD, "refs/heads/a");
		RefUpdate updateRef = db.updateRef("refs/heads/a");
		updateRef.setNewObjectId(rb);
		updateRef.setRefLogMessage("Setup", false);
		assertEquals(Result.FAST_FORWARD, updateRef.update());
		ObjectId oldHead = db.resolve(Constants.HEAD);
		assertEquals(oldHead, rb); // assumption for this test
		writeReflog(db, rb, "Just a message", "refs/heads/a");
		assertTrue("internal check, we have a log", new File(db.getDirectory(),
				"logs/refs/heads/a").exists());

		// Now this is our test
		RefRename renameRef = db.renameRef("refs/heads/a", "refs/heads/a/b");
		Result result = renameRef.rename();
		assertEquals(Result.RENAMED, result);
		assertNull(db.resolve("refs/heads/a"));
		assertEquals(rb, db.resolve("refs/heads/a/b"));
		RefDatabase refDb = db.getRefDatabase();
		assertEquals(3, refDb.getReflogReader("refs/heads/a/b")
				.getReverseEntries().size());
		assertEquals("Branch: renamed a to a/b",
				refDb.getReflogReader("refs/heads/a/b").getReverseEntries()
						.get(0).getComment());
		assertEquals("Just a message", refDb.getReflogReader("refs/heads/a/b")
				.getReverseEntries().get(1).getComment());
		assertEquals("Setup", refDb.getReflogReader("refs/heads/a/b")
				.getReverseEntries().get(2).getComment());
		// same thing was logged to HEAD
		assertEquals("Branch: renamed a to a/b", refDb.getReflogReader("HEAD")
				.getReverseEntries().get(0).getComment());
	}

	@Test
	public void testRenameRefNameColission2avoided() throws IOException {
		// setup
		ObjectId rb = db.resolve("refs/heads/b");
		writeSymref(Constants.HEAD, "refs/heads/prefix/a");
		RefUpdate updateRef = db.updateRef("refs/heads/prefix/a");
		updateRef.setNewObjectId(rb);
		updateRef.setRefLogMessage("Setup", false);
		updateRef.setForceUpdate(true);
		assertEquals(Result.FORCED, updateRef.update());
		ObjectId oldHead = db.resolve(Constants.HEAD);
		assertEquals(oldHead, rb); // assumption for this test
		writeReflog(db, rb, "Just a message", "refs/heads/prefix/a");
		assertTrue("internal check, we have a log", new File(db.getDirectory(),
				"logs/refs/heads/prefix/a").exists());

		// Now this is our test
		RefRename renameRef = db.renameRef("refs/heads/prefix/a",
				"refs/heads/prefix");
		Result result = renameRef.rename();
		assertEquals(Result.RENAMED, result);

		assertNull(db.resolve("refs/heads/prefix/a"));
		assertEquals(rb, db.resolve("refs/heads/prefix"));
		RefDatabase refDb = db.getRefDatabase();
		assertEquals(3, refDb.getReflogReader("refs/heads/prefix")
				.getReverseEntries().size());
		assertEquals("Branch: renamed prefix/a to prefix",
				refDb.getReflogReader("refs/heads/prefix").getReverseEntries()
						.get(0).getComment());
		assertEquals("Just a message",
				refDb.getReflogReader("refs/heads/prefix").getReverseEntries()
						.get(1).getComment());
		assertEquals("Setup", refDb.getReflogReader("refs/heads/prefix")
				.getReverseEntries().get(2).getComment());
		assertEquals("Branch: renamed prefix/a to prefix",
				refDb.getReflogReader("HEAD").getReverseEntries().get(0)
						.getComment());
	}

	@Test
	public void testCreateMissingObject() throws IOException {
		String name = "refs/heads/abc";
		ObjectId bad =
				ObjectId.fromString("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
		RefUpdate ru = db.updateRef(name);
		ru.setNewObjectId(bad);
		Result update = ru.update();
		assertEquals(Result.REJECTED_MISSING_OBJECT, update);

		Ref ref = db.exactRef(name);
		assertNull(ref);
	}

	@Test
	public void testUpdateMissingObject() throws IOException {
		String name = "refs/heads/abc";
		RefUpdate ru = updateRef(name);
		Result update = ru.update();
		assertEquals(Result.NEW, update);
		ObjectId oldId = ru.getNewObjectId();

		ObjectId bad =
				ObjectId.fromString("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
		ru = db.updateRef(name);
		ru.setNewObjectId(bad);
		update = ru.update();
		assertEquals(Result.REJECTED_MISSING_OBJECT, update);

		Ref ref = db.exactRef(name);
		assertNotNull(ref);
		assertEquals(oldId, ref.getObjectId());
	}

	@Test
	public void testForceUpdateMissingObject() throws IOException {
		String name = "refs/heads/abc";
		RefUpdate ru = updateRef(name);
		Result update = ru.update();
		assertEquals(Result.NEW, update);
		ObjectId oldId = ru.getNewObjectId();

		ObjectId bad =
				ObjectId.fromString("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
		ru = db.updateRef(name);
		ru.setNewObjectId(bad);
		update = ru.forceUpdate();
		assertEquals(Result.REJECTED_MISSING_OBJECT, update);

		Ref ref = db.exactRef(name);
		assertNotNull(ref);
		assertEquals(oldId, ref.getObjectId());
	}

	private static void writeReflog(Repository db, ObjectId newId, String msg,
			String refName) throws IOException {
		RefDirectory refs = (RefDirectory) db.getRefDatabase();
		RefDirectoryUpdate update = refs.newUpdate(refName, true);
		update.setNewObjectId(newId);
		refs.log(false, update, msg, true);
	}

	private static class SubclassedId extends ObjectId {
		SubclassedId(AnyObjectId src) {
			super(src);
		}
	}
}
