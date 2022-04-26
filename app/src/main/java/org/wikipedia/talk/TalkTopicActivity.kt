package org.wikipedia.talk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collect
import org.wikipedia.Constants
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.activity.BaseActivity
import org.wikipedia.analytics.EditFunnel
import org.wikipedia.analytics.LoginFunnel
import org.wikipedia.analytics.TalkFunnel
import org.wikipedia.analytics.eventplatform.EditAttemptStepEvent
import org.wikipedia.auth.AccountUtil
import org.wikipedia.databinding.ActivityTalkTopicBinding
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.discussiontools.ThreadItem
import org.wikipedia.edit.EditHandler
import org.wikipedia.edit.EditSectionActivity
import org.wikipedia.history.HistoryEntry
import org.wikipedia.login.LoginActivity
import org.wikipedia.notifications.AnonymousNotificationHelper
import org.wikipedia.page.*
import org.wikipedia.page.linkpreview.LinkPreviewDialog
import org.wikipedia.readinglist.AddToReadingListDialog
import org.wikipedia.util.*
import org.wikipedia.views.UserMentionInputView

class TalkTopicActivity : BaseActivity(), LinkPreviewDialog.Callback, UserMentionInputView.Listener {
    private lateinit var binding: ActivityTalkTopicBinding
    private lateinit var talkFunnel: TalkFunnel
    private lateinit var editFunnel: EditFunnel
    private lateinit var linkHandler: TalkLinkHandler
    private lateinit var textWatcher: TextWatcher

    private val viewModel: TalkTopicViewModel by viewModels { TalkTopicViewModel.Factory(intent.extras!!) }
    private val threadAdapter = TalkReplyItemAdapter()
    private var replyActive = false
    private var undone = false
    private var undoneBody = ""
    private var undoneSubject = ""
    private var showUndoSnackbar = false
    private val bottomSheetPresenter = ExclusiveBottomSheetPresenter()
    private var currentRevision: Long = 0
    private var revisionForUndo: Long = 0
    private var userMentionScrolled = false

    private val linkMovementMethod = LinkMovementMethodExt { url, title, linkText, x, y ->
        linkHandler.onUrlClick(url, title, linkText, x, y)
    }
    private val requestLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == LoginActivity.RESULT_LOGIN_SUCCESS) {
            updateEditLicenseText()
            editFunnel.logLoginSuccess()
            FeedbackUtil.showMessage(this, R.string.login_success_toast)
        } else {
            editFunnel.logLoginFailure()
        }
    }
    private val requestEditSource = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == EditHandler.RESULT_REFRESH_PAGE) {
            // TODO: maybe add funnel?
            loadTopics()
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTalkTopicBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.replyToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = ""
        if (intent.hasExtra(EXTRA_SUBJECT)) undoneSubject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""
        if (intent.hasExtra(EXTRA_BODY)) undoneBody = intent.getStringExtra(EXTRA_BODY) ?: ""
        linkHandler = TalkLinkHandler(this)
        linkHandler.wikiSite = viewModel.pageTitle.wikiSite

        L10nUtil.setConditionalLayoutDirection(binding.talkRefreshView, viewModel.pageTitle.wikiSite.languageCode)
        binding.talkRefreshView.setColorSchemeResources(ResourceUtil.getThemedAttributeId(this, R.attr.colorAccent))

        binding.talkRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.talkRecyclerView.adapter = threadAdapter

        binding.talkErrorView.backClickListener = View.OnClickListener {
            finish()
        }
        binding.talkErrorView.retryClickListener = View.OnClickListener {
            loadTopics()
        }

        binding.talkReplyButton.setOnClickListener {
            talkFunnel.logReplyClick()
            editFunnel.logStart()
            EditAttemptStepEvent.logInit(viewModel.pageTitle)
            replyClicked()
        }

        textWatcher = binding.replySubjectText.doOnTextChanged { _, _, _, _ ->
            binding.replySubjectLayout.error = null
            binding.replyInputView.textInputLayout.error = null
        }
        binding.replySaveButton.setOnClickListener {
            onSaveClicked()
        }

        binding.talkRefreshView.isEnabled = !isNewTopic()
        binding.talkRefreshView.setOnRefreshListener {
            talkFunnel.logRefresh()
            loadTopics()
        }

        binding.talkScrollContainer.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
            if (binding.talkSubjectView.isVisible) {
                binding.talkToolbarSubjectView.visibility = if (binding.talkScrollContainer.scrollY >
                        binding.talkSubjectView.height) View.VISIBLE else View.INVISIBLE
            }
        })

        binding.talkReplyButton.visibility = View.GONE

        binding.replyInputView.wikiSite = viewModel.pageTitle.wikiSite
        binding.replyInputView.listener = this

        talkFunnel = TalkFunnel(viewModel.pageTitle, intent.getSerializableExtra(Constants.INTENT_EXTRA_INVOKE_SOURCE) as Constants.InvokeSource)
        talkFunnel.logOpenTopic()

        editFunnel = EditFunnel(WikipediaApp.getInstance(), viewModel.pageTitle)
        updateEditLicenseText()

        lifecycleScope.launchWhenCreated {
            viewModel.uiState.collect {
                when (it) {
                    is TalkTopicViewModel.UiState.LoadTopic -> updateOnSuccess(it.threadItems)
                    is TalkTopicViewModel.UiState.LoadError -> updateOnError(it.throwable)
                    is TalkTopicViewModel.UiState.DoEdit -> onSaveSuccess(it.editResult.newRevId)
                    is TalkTopicViewModel.UiState.UndoEdit -> onSaveSuccess(it.edit.edit?.newRevId ?: 0)
                    is TalkTopicViewModel.UiState.EditError -> onSaveError(it.throwable)
                }
            }
        }
        onInitialLoad()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_talk_topic, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.menu_talk_topic_group)?.isVisible = !replyActive
        menu?.findItem(R.id.menu_edit_source)?.isVisible = AccountUtil.isLoggedIn
        binding.talkRefreshView.isEnabled = !replyActive
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        super.onOptionsItemSelected(item)
        return when (item.itemId) {
            R.id.menu_talk_topic_share -> {
                ShareUtil.shareText(this, getString(R.string.talk_share_discussion_subject, viewModel.topic?.html?.ifEmpty { getString(R.string.talk_no_subject) }), viewModel.pageTitle.uri + "#" + StringUtil.addUnderscores(viewModel.topic?.html))
                true
            }
            R.id.menu_edit_source -> {
                requestEditSource.launch(EditSectionActivity.newIntent(this, viewModel.sectionId, undoneSubject, viewModel.pageTitle))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun replyClicked() {
        replyActive = true
        binding.talkRecyclerView.adapter?.notifyDataSetChanged()
        binding.talkScrollContainer.fullScroll(View.FOCUS_DOWN)
        binding.replySaveButton.visibility = View.VISIBLE
        binding.replyInputView.visibility = View.VISIBLE
        binding.replyInputView.maybePrepopulateUserName()

        binding.licenseText.visibility = View.VISIBLE
        binding.talkScrollContainer.post {
            if (!isDestroyed) {
                binding.replyInputView.editText.requestFocus()
                DeviceUtil.showSoftKeyboard(binding.replyInputView.editText)
                binding.talkScrollContainer.postDelayed({
                    binding.talkScrollContainer.smoothScrollTo(0, binding.talkScrollContainer.height * 4)
                }, 500)
            }
        }
        binding.talkReplyButton.hide()
        if (undone) {
            binding.replyInputView.editText.setText(undoneBody)
            binding.replyInputView.editText.setSelection(binding.replyInputView.editText.text.toString().length)
        }
        invalidateOptionsMenu()
    }

    public override fun onDestroy() {
        binding.replySubjectText.removeTextChangedListener(textWatcher)
        binding.replyInputView.editText.removeTextChangedListener(textWatcher)
        super.onDestroy()
    }

    private fun onInitialLoad() {
        if (isNewTopic()) {
            replyActive = true
            title = getString(R.string.talk_new_topic)
            binding.talkSubjectView.visibility = View.GONE
            binding.talkToolbarSubjectView.visibility = View.INVISIBLE
            binding.talkProgressBar.visibility = View.GONE
            binding.talkErrorView.visibility = View.GONE
            binding.replySaveButton.visibility = View.VISIBLE
            binding.replySubjectLayout.visibility = View.VISIBLE
            binding.replyInputView.textInputLayout.hint = getString(R.string.talk_message_hint)
            binding.replySubjectText.setText(undoneSubject)
            binding.replyInputView.editText.setText(undoneBody)
            binding.replyInputView.visibility = View.VISIBLE
            binding.licenseText.visibility = View.VISIBLE
            binding.replySubjectLayout.requestFocus()
            editFunnel.logStart()
            EditAttemptStepEvent.logInit(viewModel.pageTitle)
        } else {
            replyActive = false
            binding.replyInputView.editText.setText("")
            binding.replySaveButton.visibility = View.GONE
            binding.replySubjectLayout.visibility = View.GONE
            binding.replyInputView.visibility = View.GONE
            binding.replyInputView.textInputLayout.hint = getString(R.string.talk_reply_hint)
            binding.licenseText.visibility = View.GONE
            binding.talkProgressBar.visibility = View.VISIBLE
            binding.talkErrorView.visibility = View.GONE
            DeviceUtil.hideSoftKeyboard(this)
        }
        invalidateOptionsMenu()
    }

    private fun loadTopics() {
        if (isNewTopic()) {
            return
        }
        binding.talkProgressBar.visibility = View.VISIBLE
        binding.talkErrorView.visibility = View.GONE
        viewModel.loadTopic()
    }

    private fun updateOnSuccess(threadItems: List<ThreadItem>) {

        binding.talkProgressBar.visibility = View.GONE

        // TODO:
        // viewModel.seenTopic(topic?.id)

        // TODO: Discuss this
        // currentRevision = talkTopic.revision

        if (replyActive || shouldHideReplyButton()) {
            binding.talkReplyButton.hide()
        } else {
            binding.talkReplyButton.show()
            binding.talkReplyButton.isEnabled = true
            binding.talkReplyButton.alpha = 1.0f
        }
        binding.talkRefreshView.isRefreshing = false

        val titleStr = StringUtil.fromHtml(viewModel.topic?.html).toString().trim()
        binding.talkSubjectView.text = titleStr.ifEmpty { getString(R.string.talk_no_subject) }
        binding.talkSubjectView.visibility = View.VISIBLE
        binding.talkToolbarSubjectView.text = binding.talkSubjectView.text
        binding.talkToolbarSubjectView.visibility = View.INVISIBLE
        binding.talkRecyclerView.adapter?.notifyDataSetChanged()
        binding.replyInputView.userNameHints = parseUserNamesFromTopic()

        maybeShowUndoSnackbar()
    }

    private fun updateOnError(t: Throwable) {
        binding.talkProgressBar.visibility = View.GONE
        binding.talkRefreshView.isRefreshing = false
        binding.talkReplyButton.hide()
        binding.talkErrorView.visibility = View.VISIBLE
        binding.talkErrorView.setError(t)
    }

    private fun isNewTopic(): Boolean {
        return viewModel.topicId == TalkTopicsActivity.NEW_TOPIC_ID
    }

    private fun shouldHideReplyButton(): Boolean {
        // TODO: revisit this
        // Hide the reply button when:
        // a) The topic ID is -1, which means the API couldn't parse it properly (TODO: wait until fixed)
        // b) The name of the topic is empty, implying that this is the topmost "header" section.
        return viewModel.topicId == "" || viewModel.topic?.html.orEmpty().trim().isEmpty()
    }

    internal inner class TalkReplyHolder internal constructor(view: TalkThreadItemView) : RecyclerView.ViewHolder(view), TalkThreadItemView.Callback {
        fun bindItem(item: ThreadItem) {
            (itemView as TalkThreadItemView).let {
                it.bindItem(item, linkMovementMethod)
                it.callback = this
            }
        }

        override fun onExpandClick(item: ThreadItem) {
            viewModel.toggleItemExpanded(item).dispatchUpdatesTo(threadAdapter)
        }
    }

    internal inner class TalkReplyItemAdapter : RecyclerView.Adapter<TalkReplyHolder>() {
        override fun getItemCount(): Int {
            return viewModel.flattenedThreadItems.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): TalkReplyHolder {
            return TalkReplyHolder(TalkThreadItemView(parent.context))
        }

        override fun onBindViewHolder(holder: TalkReplyHolder, pos: Int) {
            holder.bindItem(viewModel.flattenedThreadItems[pos])
        }
    }

    internal inner class TalkLinkHandler internal constructor(context: Context) : LinkHandler(context) {
        private var lastX: Int = 0
        private var lastY: Int = 0

        fun onUrlClick(url: String, title: String?, linkText: String, x: Int, y: Int) {
            lastX = x
            lastY = y
            super.onUrlClick(url, title, linkText)
        }

        override fun onMediaLinkClicked(title: PageTitle) {
            // TODO
        }

        override fun onDiffLinkClicked(title: PageTitle, revisionId: Long) {
            // TODO
        }

        override lateinit var wikiSite: WikiSite

        override fun onPageLinkClicked(anchor: String, linkText: String) {
            // TODO
        }

        override fun onInternalLinkClicked(title: PageTitle) {
            UserTalkPopupHelper.show(this@TalkTopicActivity, bottomSheetPresenter, title, false, lastX, lastY,
                    Constants.InvokeSource.TALK_ACTIVITY, HistoryEntry.SOURCE_TALK_TOPIC)
        }
    }

    private fun onSaveClicked() {
        val subject = binding.replySubjectText.text.toString().trim()
        var body = binding.replyInputView.editText.getParsedText(viewModel.pageTitle.wikiSite).trim()
        undoneBody = body
        undoneSubject = subject

        editFunnel.logSaveAttempt()
        EditAttemptStepEvent.logSaveAttempt(viewModel.pageTitle)

        if (isNewTopic() && subject.isEmpty()) {
            binding.replySubjectLayout.error = getString(R.string.talk_subject_empty)
            binding.replySubjectLayout.requestFocus()
            return
        } else if (body.isEmpty()) {
            binding.replyInputView.textInputLayout.error = getString(R.string.talk_message_empty)
            binding.replyInputView.textInputLayout.requestFocus()
            return
        }

        // TODO: get level of replied-to item
        val topicDepth = viewModel.topic?.level ?: 1

        body = addDefaultFormatting(body, topicDepth, isNewTopic())

        binding.talkProgressBar.visibility = View.VISIBLE
        binding.replySaveButton.isEnabled = false

        talkFunnel.logEditSubmit()

        // TODO: move this logic to another class
        /*
        if (isNewTopic()) {
            viewModel.doSave(subject, body)
        } else {
            // TODO: give comment id
            viewModel.doSaveReply("", body)
        }
        */
    }

    private fun onSaveSuccess(newRevision: Long) {

        // TODO: should we add logic of checking updated revision?
        revisionForUndo = newRevision
        showUndoSnackbar = true
        AnonymousNotificationHelper.onEditSubmitted()

        binding.talkProgressBar.visibility = View.GONE
        binding.replySaveButton.isEnabled = true
        editFunnel.logSaved(newRevision)
        EditAttemptStepEvent.logSaveSuccess(viewModel.pageTitle)

        if (isNewTopic()) {
            Intent().let {
                it.putExtra(RESULT_NEW_REVISION_ID, newRevision)
                it.putExtra(EXTRA_TOPIC, viewModel.topicId)
                it.putExtra(EXTRA_SUBJECT, undoneSubject)
                it.putExtra(EXTRA_BODY, undoneBody)
                setResult(RESULT_EDIT_SUCCESS, it)
                finish()
            }
        } else {
            onInitialLoad()
            loadTopics()
        }
    }

    private fun onSaveError(t: Throwable) {
        editFunnel.logError(t.message)
        EditAttemptStepEvent.logSaveFailure(viewModel.pageTitle)
        binding.talkProgressBar.visibility = View.GONE
        binding.replySaveButton.isEnabled = true
        FeedbackUtil.showError(this, t)
    }

    private fun maybeShowUndoSnackbar() {
        if (undone) {
            replyClicked()
            undone = false
            return
        }
        if (showUndoSnackbar) {
            FeedbackUtil.makeSnackbar(this, getString(R.string.talk_response_submitted), FeedbackUtil.LENGTH_DEFAULT)
                .setAnchorView(binding.talkReplyButton)
                .setAction(R.string.talk_snackbar_undo) {
                    undone = true
                    binding.talkReplyButton.isEnabled = false
                    binding.talkReplyButton.alpha = 0.5f
                    binding.talkProgressBar.visibility = View.VISIBLE
                    // TODO
                    // viewModel.undoSave(revisionForUndo, "", "", "")
                }
                .show()
            showUndoSnackbar = false
        }
    }

    private fun updateEditLicenseText() {
        binding.licenseText.text = StringUtil.fromHtml(getString(if (AccountUtil.isLoggedIn) R.string.edit_save_action_license_logged_in else R.string.edit_save_action_license_anon,
                getString(R.string.terms_of_use_url),
                getString(R.string.cc_by_sa_3_url)))
        binding.licenseText.movementMethod = LinkMovementMethodExt { url: String ->
            if (url == "https://#login") {
                val loginIntent = LoginActivity.newIntent(this,
                        LoginFunnel.SOURCE_EDIT, editFunnel.sessionToken)
                requestLogin.launch(loginIntent)
            } else {
                UriUtil.handleExternalLink(this, Uri.parse(url))
            }
        }
    }

    override fun onLinkPreviewLoadPage(title: PageTitle, entry: HistoryEntry, inNewTab: Boolean) {
        startActivity(if (inNewTab) PageActivity.newIntentForNewTab(this, entry, title) else
            PageActivity.newIntentForCurrentTab(this, entry, title, false))
    }

    override fun onLinkPreviewCopyLink(title: PageTitle) {
        ClipboardUtil.setPlainText(this, null, title.uri)
        FeedbackUtil.showMessage(this, R.string.address_copied)
    }

    override fun onLinkPreviewAddToList(title: PageTitle) {
        bottomSheetPresenter.show(supportFragmentManager,
                AddToReadingListDialog.newInstance(title, Constants.InvokeSource.TALK_ACTIVITY))
    }

    override fun onLinkPreviewShareLink(title: PageTitle) {
        ShareUtil.shareText(this, title)
    }

    override fun onBackPressed() {
        if (replyActive && !isNewTopic()) {
            onInitialLoad()
        } else {
            setResult(RESULT_BACK_FROM_TOPIC)
            super.onBackPressed()
        }
    }

    override fun onUserMentionListUpdate() {
        if (!replyActive) {
            return
        }
        binding.licenseText.isVisible = false
        binding.talkScrollContainer.post {
            if (!isDestroyed && !userMentionScrolled) {
                binding.talkScrollContainer.smoothScrollTo(0, binding.root.height * 4)
                userMentionScrolled = true
            }
        }
    }

    override fun onUserMentionComplete() {
        if (!replyActive) {
            return
        }
        userMentionScrolled = false
        binding.licenseText.isVisible = true
    }

    private fun parseUserNamesFromTopic(): Set<String> {
        val userNames = mutableSetOf<String>()
        // Go through our list of replies under the current topic, and collect any links to user
        // names, making sure to store them in reverse order, so that the last user name mentioned
        // in a response will appear first in the list of hints when searching for mentions.
        // TODO: search only up to the replied-to item
        viewModel.flattenedThreadItems.forEach {
            var start = 0
            val userList = mutableListOf<String>()
            while (true) {
                val searchStr = "title=\""
                start = it.html.indexOf(searchStr, startIndex = start)
                if (start < 0) {
                    break
                }
                start += searchStr.length
                val end = it.html.indexOf("\"", startIndex = start)
                if (end <= start) {
                    break
                }
                val name = it.html.substring(start, end)
                val title = PageTitle(name, viewModel.pageTitle.wikiSite)
                if (title.namespace() == Namespace.USER || title.namespace() == Namespace.USER_TALK) {
                    userList.add(0, StringUtil.removeUnderscores(title.text))
                }
                start = end
            }
            userNames.addAll(userList)
        }
        return userNames
    }

    companion object {
        const val EXTRA_PAGE_TITLE = "pageTitle"
        const val EXTRA_TOPIC = "topicId"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_BODY = "body"
        const val RESULT_EDIT_SUCCESS = 1
        const val RESULT_BACK_FROM_TOPIC = 2
        const val RESULT_NEW_REVISION_ID = "newRevisionId"

        fun newIntent(context: Context,
                      pageTitle: PageTitle,
                      topicId: String,
                      invokeSource: Constants.InvokeSource,
                      undoneSubject: String? = null,
                      undoneBody: String? = null): Intent {
            return Intent(context, TalkTopicActivity::class.java)
                    .putExtra(EXTRA_PAGE_TITLE, pageTitle)
                    .putExtra(EXTRA_TOPIC, topicId)
                    .putExtra(EXTRA_SUBJECT, undoneSubject ?: "")
                    .putExtra(EXTRA_BODY, undoneBody ?: "")
                    .putExtra(Constants.INTENT_EXTRA_INVOKE_SOURCE, invokeSource)
        }

        fun addDefaultFormatting(text: String, topicDepth: Int, newTopic: Boolean = false): String {
            var body = ":".repeat(if (newTopic) 0 else topicDepth + 1) + text
            // if the message is not signed, then sign it explicitly
            if (!body.endsWith("~~~~")) {
                body += " ~~~~"
            }
            if (!newTopic) {
                // add two explicit newlines at the beginning, to delineate this message as a new paragraph.
                body = "\n\n" + body
            }
            return body
        }
    }
}
