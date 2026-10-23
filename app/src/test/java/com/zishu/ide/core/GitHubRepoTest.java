package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.project.GitHubRepo;

import org.junit.Test;

public class GitHubRepoTest {
    @Test
    public void parsesCommonForms() {
        String[] same = {"octocat/Hello-World", "https://github.com/octocat/Hello-World", "https://github.com/octocat/Hello-World.git",
                "github.com/octocat/Hello-World/", "git@github.com:octocat/Hello-World.git", "http://www.github.com/octocat/Hello-World?tab=x"};
        for (String s : same) {
            GitHubRepo r = GitHubRepo.parse(s);
            assertEquals(s, "octocat", r.owner);
            assertEquals(s, "Hello-World", r.repo);
            assertNull(s, r.ref);
        }
    }

    @Test
    public void treeUrlGivesBranch() {
        GitHubRepo r = GitHubRepo.parse("https://github.com/o/r/tree/feature/x");
        assertEquals("feature/x", r.ref);
        assertEquals("https://api.github.com/repos/o/r/zipball/feature/x", r.zipballUrl());
        assertEquals("https://api.github.com/repos/o/r/zipball", GitHubRepo.parse("o/r").zipballUrl());
    }

    @Test
    public void rejectsNonGithubAndOddInput() {
        assertNull(GitHubRepo.parse("https://gitlab.com/a/b"));
        assertNull(GitHubRepo.parse("not a repo"));
        assertNull(GitHubRepo.parse(""));
        assertNull(GitHubRepo.parse("../etc"));
        assertNull(GitHubRepo.parse("https://github.com/onlyowner"));
    }

    @Test
    public void redirectHostsAreRestricted() {
        assertTrue(GitHubRepo.trustedRedirectHost("codeload.github.com"));
        assertTrue(GitHubRepo.trustedRedirectHost("api.github.com"));
        assertTrue(GitHubRepo.trustedRedirectHost("objects.githubusercontent.com"));
        assertFalse(GitHubRepo.trustedRedirectHost("github.com.evil.com"));
        assertFalse(GitHubRepo.trustedRedirectHost("evilgithub.com"));
    }
}
