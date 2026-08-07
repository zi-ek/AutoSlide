const { Octokit } = require("@octokit/rest");

module.exports = async (req, res) => {
  // 从环境变量中安全读取 Token（不要写死在代码里）
  const GITHUB_TOKEN = process.env.GITHUB_TOKEN;
  const REPO_OWNER = "zi-ek";
  const REPO_NAME = "AutoSlide";
  const FILE_PATH = "stats.json";

  if (!GITHUB_TOKEN) {
    return res.status(500).json({ error: "Server missing GITHUB_TOKEN" });
  }

  const octokit = new Octokit({ auth: GITHUB_TOKEN });

  try {
    let currentCount = 0;
    let sha = undefined;

    // 1. 尝试读取现有的 stats.json
    try {
      const { data } = await octokit.repos.getContent({
        owner: REPO_OWNER,
        repo: REPO_NAME,
        path: FILE_PATH,
      });
      sha = data.sha;
      const content = Buffer.from(data.content, "base64").toString();
      currentCount = JSON.parse(content).install_count || 0;
    } catch (e) {
      console.log("File not found, creating new one.");
    }

    // 2. 数量加 1
    const newContent = JSON.stringify({
      install_count: currentCount + 1,
      last_update: new Date().toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })
    }, null, 2);

    // 3. 写回 GitHub
    await octokit.repos.createOrUpdateFileContents({
      owner: REPO_OWNER,
      repo: REPO_NAME,
      path: FILE_PATH,
      message: "Update install count (via Cloud Function)",
      content: Buffer.from(newContent).toString("base64"),
      sha: sha
    });

    res.setHeader('Access-Control-Allow-Origin', '*');
    res.status(200).send("Report Success: " + (currentCount + 1));
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: error.message });
  }
};
