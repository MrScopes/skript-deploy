# Alpha
Plugin in early testing. Make sure to make your own extra backups just in case.

# skript-deploy

Deploy Skript files from a configured Git repository to your server, back up replaced files, and automatically reload the affected scripts.\
Automatic backups are performed, but still make your own just in case.

## Configuration
```yaml
repository:
  # Local staging folder used for the repository clone.
  # The plugin will clone the repository here if the folder is missing or empty.
  local-folder: 'plugins/skript-deploy/repository'

  # Repository URL.
  url: 'https://github.com/myuser/myrepo'

  # Git remote name.
  origin: 'origin'

  # Folder inside the repository containing the scripts.
  # Leave blank if the scripts are stored in the repository root.
  scripts-folder: ''

  credentials:
    # Leave blank for public repositories.
    # For private repositories, enter the username and access token required
    # by your Git provider.
    username: ''
    secret: ''

# Final folder where Skript loads scripts from.
scripts-folder: 'plugins/Skript/scripts'

# Existing live scripts are backed up here before replacement or deletion.
backups-folder: 'plugins/skript-deploy/backups'

# Permission required to use /deploy.
# Leave blank to allow everyone.
permission: 'skript-deploy.deploy'
```

## Simple Setup

1. Create a repository on GitHub, GitLab, etc.
2. Upload your Skript files.
3. Configure `repository.url`.
4. If your scripts are inside a folder on the repository (for example `scripts/`), configure `scripts-folder`.
5. Add credentials if the repository is private.
6. Run:
```
/deploy reload
```

7. Deploy your scripts:
```
/deploy
```

By default, skript-deploy clones the repository into:
```
plugins/skript-deploy/repository
```

and deploys scripts into:
```text
plugins/Skript/scripts
```

No Git installation or command-line setup is required.

If your repository looks like:

```text
repository/
└── scripts/
    ├── commands.sk
    ├── mining.sk
    └── systems/
        └── example.sk
```

configure:

```yaml
repository:
  scripts-folder: 'scripts'
```

If your scripts are stored directly in the repository root:

```text
repository/
├── commands.sk
├── mining.sk
└── systems/
    └── example.sk
```

configure:

```yaml
repository:
  scripts-folder: ''
```

### Custom Local Folder

You may change `repository.local-folder` to any location.

- If the folder does **not** exist or is empty, skript-deploy will automatically clone the repository.
- If the folder already contains files, it **must already be a valid clone** of the configured repository. skript-deploy will not clone into a non-empty folder.

## Advanced Setup

You can also make an existing server folder the repository's working tree.

For example, if you want `plugins/Skript` to be the repository root:

```text
plugins/
└── Skript/
    ├── .git/
    ├── scripts/
    └── config.sk
```

Initialize the repository from inside `plugins/Skript`:

```bash
git init
git remote add origin https://github.com/myuser/myrepo.git
git add .
git commit -m "Initial commit"
git branch -M main
git push -u origin main
```

Then configure:

```yaml
repository:
  local-folder: 'plugins/Skript'
  url: 'https://github.com/myuser/myrepo'
  origin: 'origin'
  scripts-folder: 'scripts'

scripts-folder: 'plugins/Skript/scripts'
```

Reload skript-deploy:

```text
/deploy reload
```

This mode is intended for users who want the server's Skript folder to also be a Git working tree.

> **Note:** skript-deploy uses JGit internally and does **not** require Git to be installed on the server. Git is only needed if you want to manually commit and push changes from the server itself, good for testing sake.

## Deployment Behavior

When `/deploy` is executed, skript-deploy:

1. Fetches the latest repository changes.
2. Detects added, modified, renamed, and deleted scripts.
3. Backs up existing live scripts before replacing or deleting them.
4. Copies updated scripts into the live Skript folder.
5. Removes scripts deleted from the repository.
6. Reloads only the changed scripts.
7. Performs a full Skript reload if any script was deleted or renamed.

## Commands
Default permission: `skript-deploy.reload`
### `/deploy`
Deploys the latest repository changes and reloads Skript.

### `/deploy reload`
Reloads the skript-deploy configuration and reinitializes the configured repository.