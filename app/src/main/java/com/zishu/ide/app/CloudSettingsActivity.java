package com.zishu.ide.app;

import com.zishu.ide.R;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;

import com.zishu.ide.core.build.CloudBuildEngine;
import com.zishu.ide.core.build.GitHubApi;
import com.zishu.ide.core.build.UrlTransport;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Settings for the GitHub Actions cloud build: the user's own build repository and a fine-grained
 * access token. The token is stored encrypted with an Android Keystore key and sent only to
 * api.github.com. It is never part of the APK.
 */
public final class CloudSettingsActivity extends BaseActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui.Field tokenField;
    private Ui.Field repoField;
    private Switch installSwitch;
    private Switch publicSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.topBar(this, pal, "Cloud build", "GitHub Actions", R.drawable.ic_arrow_back, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        root.addView(Ui.divider(this, pal));
        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(24));

        page.addView(Ui.banner(this, pal, R.drawable.ic_cloud, pal.primary, "How it works",
                "To build, Zishu IDE uploads your project to a repository you own, runs a GitHub Actions workflow there, "
                        + "and downloads the APK it produces. Use a private repository: the upload contains your source code."));

        page.addView(Ui.section(this, pal, "Repository"));
        repoField = Ui.field(this, pal, "Build repository", "yourname/zishu-builds", prefs.cloudRepo(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        page.addView(repoField.view);

        page.addView(Ui.section(this, pal, "Access token"));
        boolean has = TokenStore.has(this);
        tokenField = Ui.field(this, pal, "Fine-grained personal access token",
                has ? "Saved. Leave empty to keep it." : "github_pat_…", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        page.addView(tokenField.view);
        page.addView(Ui.text(this, pal, GitHubApi.permissionHelp() + " Create it at GitHub → Settings → Developer settings → "
                + "Fine-grained tokens, select only your build repository, and set a short expiry.", Ui.Style.CAPTION),
                Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 8, 0, 0));
        page.addView(Ui.text(this, pal, TokenStore.canPersist()
                ? "The token is encrypted with a key held by the Android Keystore and is only sent to api.github.com."
                : "This Android version cannot keep the token encrypted on disk, so it is held in memory only and must be entered again after the app is closed.",
                Ui.Style.CAPTION), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 6, 0, 0));

        page.addView(Ui.section(this, pal, "Options"));
        installSwitch = Ui.toggle(this, pal, "Install or update the build workflow in the repository", prefs.cloudInstallWorkflow());
        page.addView(installSwitch);
        page.addView(Ui.text(this, pal, "Off by default. Without this, the repository must already contain .github/workflows/"
                + CloudBuildEngine.WORKFLOW_FILE + " (the test below tells you). With it, Zishu IDE commits that one file when it is missing or outdated.",
                Ui.Style.CAPTION), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 0, 12));
        publicSwitch = Ui.toggle(this, pal, "Allow a public repository", prefs.cloudAllowPublic());
        page.addView(publicSwitch);
        page.addView(Ui.text(this, pal, "Builds in a public repository make your project's source public. Leave this off unless that is what you want.",
                Ui.Style.CAPTION), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 0, 12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(Ui.button(this, pal, "Save", R.drawable.ic_check, Ui.Kind.FILLED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(true);
            }
        }));
        actions.addView(Ui.button(this, pal, "Save and test connection", R.drawable.ic_cloud, Ui.Kind.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (save(false)) {
                    testConnection();
                }
            }
        }), Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        if (has) {
            actions.addView(Ui.button(this, pal, "Remove saved token", R.drawable.ic_trash, Ui.Kind.DANGER, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    TokenStore.clear(CloudSettingsActivity.this);
                    toast("Token removed.");
                    recreate();
                }
            }), Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        }
        page.addView(actions, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        sv.addView(page);
        root.addView(sv, Ui.weight(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setScreen(root);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private boolean save(boolean report) {
        String repo = repoField.value().trim();
        repoField.setError(null);
        tokenField.setError(null);
        if (repo.length() > 0 && GitHubApi.parseRepo(repo) == null) {
            repoField.setError("Use the form owner/repository, for example yourname/zishu-builds.");
            return false;
        }
        String token = tokenField.value().trim();
        if (token.length() > 0) {
            if (token.length() < 20 || token.indexOf(' ') >= 0 || token.indexOf('\n') >= 0) {
                tokenField.setError("This does not look like a GitHub token.");
                return false;
            }
            try {
                TokenStore.save(this, token);
            } catch (GeneralSecurityException e) {
                tokenField.setError("The token could not be stored securely on this device: " + e.getMessage());
                return false;
            } catch (IOException e) {
                tokenField.setError("The token could not be stored: " + e.getMessage());
                return false;
            }
            tokenField.edit.setText("");
        }
        prefs.setCloudRepo(repo);
        prefs.setCloudInstallWorkflow(installSwitch.isChecked());
        prefs.setCloudAllowPublic(publicSwitch.isChecked());
        if (report) {
            toast("Saved.");
        }
        return true;
    }

    private void testConnection() {
        final String token = TokenStore.load(this);
        final String[] or = GitHubApi.parseRepo(prefs.cloudRepo());
        if (token == null || or == null) {
            Ui.message(this, pal, "Cannot test yet", "Enter a repository and a token first.");
            return;
        }
        final android.app.AlertDialog wait = Ui.progress(this, pal, "Contacting GitHub…");
        wait.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String title = "Connection OK";
                StringBuilder body = new StringBuilder();
                try {
                    GitHubApi api = new GitHubApi(new UrlTransport(), token, or[0], or[1]);
                    GitHubApi.RepoInfo info = api.getRepo();
                    body.append(or[0]).append('/').append(or[1]).append(" is ").append(info.isPrivate ? "private" : "PUBLIC").append(".\n");
                    if (!info.isPrivate && !prefs.cloudAllowPublic()) {
                        title = "Public repository";
                        body.append("Builds are blocked here, because uploading would make your source code public. ")
                                .append("Use a private repository or enable \"Allow a public repository\".\n");
                    }
                    if (!info.canPush) {
                        title = "Token cannot write";
                        body.append("The token cannot push to this repository. ").append(GitHubApi.permissionHelp()).append('\n');
                    }
                    String sha = api.getFileSha(CloudBuildEngine.WORKFLOW_PATH, info.defaultBranch);
                    if (sha == null) {
                        body.append("The build workflow is not installed in ").append(info.defaultBranch).append(". ")
                                .append(prefs.cloudInstallWorkflow() ? "It will be installed on the first build."
                                        : "Enable \"Install or update the build workflow\", or add it yourself, before building.").append('\n');
                    } else {
                        body.append("The build workflow is installed.\n");
                    }
                } catch (GitHubApi.ApiException e) {
                    title = "GitHub refused the request";
                    body.append(e.getMessage());
                    if (e.status == 401 || e.status == 403 || e.status == 404) {
                        body.append("\n\nCheck that the token is valid, has not expired, and was granted access to this repository. ")
                                .append(GitHubApi.permissionHelp());
                    }
                } catch (IOException e) {
                    title = "Could not reach GitHub";
                    body.append(e.getMessage());
                } catch (RuntimeException e) {
                    title = "Unexpected response";
                    body.append(e.getMessage());
                } catch (com.zishu.ide.core.util.MiniJson.JsonException e) {
                    title = "Unexpected response";
                    body.append("GitHub returned data this app could not read: ").append(e.getMessage());
                }
                final String t = title;
                final String b = body.toString().trim();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        wait.dismiss();
                        if (!isFinishing()) {
                            Ui.message(CloudSettingsActivity.this, pal, t, b);
                        }
                    }
                });
            }
        }, "zishu-github-test").start();
    }
}
