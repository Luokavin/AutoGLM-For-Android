package com.kevinluo.autoglm.settings

import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.agent.AgentConfig
import com.kevinluo.autoglm.model.ModelClient
import com.kevinluo.autoglm.model.ModelConfig
import com.kevinluo.autoglm.util.LogFileManager
import com.kevinluo.autoglm.util.Logger
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Activity for configuring model and agent settings.
 *
 * Allows users to set API base URL, model name, API key, max steps, and language.
 * Supports saving and loading multiple model profiles.
 * Also provides management of task templates and custom system prompts.
 *
 */
class SettingsActivity : AppCompatActivity() {
    
    private lateinit var settingsManager: SettingsManager
    
    // Profile selector views
    private lateinit var profileSelectorLayout: TextInputLayout
    private lateinit var profileSelector: AutoCompleteTextView
    private lateinit var btnProfileMenu: ImageButton
    
    // Model settings views
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlInput: TextInputEditText
    private lateinit var modelNameLayout: TextInputLayout
    private lateinit var modelNameInput: TextInputEditText
    private lateinit var apiKeyLayout: TextInputLayout
    private lateinit var apiKeyInput: TextInputEditText
    
    // Agent settings views
    private lateinit var maxStepsLayout: TextInputLayout
    private lateinit var maxStepsInput: TextInputEditText
    private lateinit var screenshotDelayLayout: TextInputLayout
    private lateinit var screenshotDelayInput: TextInputEditText
    private lateinit var languageRadioGroup: RadioGroup
    private lateinit var languageChinese: RadioButton
    private lateinit var languageEnglish: RadioButton
    
    // Buttons
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var backBtn: android.widget.ImageButton
    
    // Task templates views
    private lateinit var templatesRecyclerView: RecyclerView
    private lateinit var emptyTemplatesText: TextView
    private lateinit var btnAddTemplate: ImageButton
    private var templatesAdapter: TaskTemplatesAdapter? = null
    private var taskTemplates: MutableList<TaskTemplate> = mutableListOf()
    
    // Advanced settings views
    private lateinit var promptCnStatus: TextView
    private lateinit var promptEnStatus: TextView
    private lateinit var btnEditPromptCn: Button
    private lateinit var btnEditPromptEn: Button
    
    // Debug logs views
    private lateinit var logSizeText: TextView
    private lateinit var btnExportLogs: Button
    private lateinit var btnClearLogs: Button
    
    // Profile data
    private var savedProfiles: List<SavedModelProfile> = emptyList()
    private var currentProfileId: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        Logger.d(TAG, "SettingsActivity created")
        
        // Setup action bar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        }
        
        settingsManager = SettingsManager(this)
        
        initViews()
        loadCurrentSettings()
        setupListeners()
    }
    
    /**
     * Initializes all view references.
     */
    private fun initViews() {
        // Profile selector
        profileSelectorLayout = findViewById(R.id.profileSelectorLayout)
        profileSelector = findViewById(R.id.profileSelector)
        btnProfileMenu = findViewById(R.id.btnProfileMenu)
        
        // Model settings
        baseUrlLayout = findViewById(R.id.baseUrlLayout)
        baseUrlInput = findViewById(R.id.baseUrlInput)
        modelNameLayout = findViewById(R.id.modelNameLayout)
        modelNameInput = findViewById(R.id.modelNameInput)
        apiKeyLayout = findViewById(R.id.apiKeyLayout)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        
        // Agent settings
        maxStepsLayout = findViewById(R.id.maxStepsLayout)
        maxStepsInput = findViewById(R.id.maxStepsInput)
        screenshotDelayLayout = findViewById(R.id.screenshotDelayLayout)
        screenshotDelayInput = findViewById(R.id.screenshotDelayInput)
        languageRadioGroup = findViewById(R.id.languageRadioGroup)
        languageChinese = findViewById(R.id.languageChinese)
        languageEnglish = findViewById(R.id.languageEnglish)
        
        // Buttons
        saveButton = findViewById(R.id.saveButton)
        resetButton = findViewById(R.id.resetButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        backBtn = findViewById(R.id.backBtn)
        
        // Task templates
        templatesRecyclerView = findViewById(R.id.templatesRecyclerView)
        emptyTemplatesText = findViewById(R.id.emptyTemplatesText)
        btnAddTemplate = findViewById(R.id.btnAddTemplate)
        
        // Setup templates RecyclerView
        templatesRecyclerView.layoutManager = LinearLayoutManager(this)
        templatesAdapter = TaskTemplatesAdapter(
            templates = taskTemplates,
            onEditClick = { template -> showEditTemplateDialog(template) },
            onDeleteClick = { template -> showDeleteTemplateDialog(template) }
        )
        templatesRecyclerView.adapter = templatesAdapter
        
        // Advanced settings
        promptCnStatus = findViewById(R.id.promptCnStatus)
        promptEnStatus = findViewById(R.id.promptEnStatus)
        btnEditPromptCn = findViewById(R.id.btnEditPromptCn)
        btnEditPromptEn = findViewById(R.id.btnEditPromptEn)
        
        // Debug logs
        logSizeText = findViewById(R.id.logSizeText)
        btnExportLogs = findViewById(R.id.btnExportLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
    }
    
    /**
     * Loads current settings from storage and displays them.
     *
     */
    private fun loadCurrentSettings() {
        Logger.d(TAG, "Loading current settings")
        val modelConfig = settingsManager.getModelConfig()
        val agentConfig = settingsManager.getAgentConfig()
        
        // Load saved profiles
        loadSavedProfiles()
        
        // Load task templates
        loadTaskTemplates()
        
        // Update system prompt status
        updatePromptStatus()
        
        // Update log size display
        updateLogSizeDisplay()
        
        // Populate model settings
        baseUrlInput.setText(modelConfig.baseUrl)
        modelNameInput.setText(modelConfig.modelName)
        apiKeyInput.setText(if (modelConfig.apiKey == "EMPTY") "" else modelConfig.apiKey)
        
        // Populate agent settings
        maxStepsInput.setText(agentConfig.maxSteps.toString())
        screenshotDelayInput.setText((agentConfig.screenshotDelayMs / 1000.0).toString())
        
        // Set language selection
        when (agentConfig.language) {
            "en" -> languageEnglish.isChecked = true
            else -> languageChinese.isChecked = true
        }
        
        // Clear any previous errors
        clearErrors()
    }
    
    /**
     * Loads saved profiles and updates the dropdown.
     */
    private fun loadSavedProfiles() {
        Logger.d(TAG, "Loading saved profiles")
        savedProfiles = settingsManager.getSavedProfiles()
        currentProfileId = settingsManager.getCurrentProfileId()
        
        // Create display names list with "New" option
        val displayNames = mutableListOf(getString(R.string.settings_new_profile))
        displayNames.addAll(savedProfiles.map { it.displayName })
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        profileSelector.setAdapter(adapter)
        
        // Set current selection
        if (currentProfileId != null) {
            val profile = savedProfiles.find { it.id == currentProfileId }
            if (profile != null) {
                profileSelector.setText(profile.displayName, false)
            } else {
                profileSelector.setText(getString(R.string.settings_new_profile), false)
            }
        } else {
            profileSelector.setText(getString(R.string.settings_new_profile), false)
        }
        
        // Update delete button visibility
        updateDeleteButtonVisibility()
    }
    
    /**
     * Updates delete button visibility based on current selection.
     */
    private fun updateDeleteButtonVisibility() {
        // No longer needed with popup menu, but keep for compatibility
    }
    
    /**
     * Sets up click listeners for all interactive views.
     */
    private fun setupListeners() {
        backBtn.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        
        saveButton.setOnClickListener {
            if (validateInput()) {
                saveSettings()
            }
        }
        
        resetButton.setOnClickListener {
            resetToDefaults()
        }
        
        testConnectionButton.setOnClickListener {
            testModelConnection()
        }
        
        // Profile selector listener
        profileSelector.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                // "New" selected - clear fields for new profile
                currentProfileId = null
                clearModelFields()
            } else {
                // Load selected profile
                val profile = savedProfiles[position - 1]
                currentProfileId = profile.id
                loadProfileToFields(profile)
            }
            settingsManager.setCurrentProfileId(currentProfileId)
            updateDeleteButtonVisibility()
        }
        
        // Profile menu button
        btnProfileMenu.setOnClickListener { view ->
            showProfileMenu(view)
        }
        
        // Add template button
        btnAddTemplate.setOnClickListener {
            showAddTemplateDialog()
        }
        
        // System prompt edit buttons
        btnEditPromptCn.setOnClickListener {
            showEditPromptDialog("cn")
        }
        btnEditPromptEn.setOnClickListener {
            showEditPromptDialog("en")
        }
        
        // Debug logs buttons
        btnExportLogs.setOnClickListener {
            exportDebugLogs()
        }
        btnClearLogs.setOnClickListener {
            showClearLogsDialog()
        }
        
        // Clear errors on text change
        baseUrlInput.setOnFocusChangeListener { _, _ -> baseUrlLayout.error = null }
        modelNameInput.setOnFocusChangeListener { _, _ -> modelNameLayout.error = null }
        maxStepsInput.setOnFocusChangeListener { _, _ -> maxStepsLayout.error = null }
        screenshotDelayInput.setOnFocusChangeListener { _, _ -> screenshotDelayLayout.error = null }
    }
    
    /**
     * Clears model configuration fields for new profile.
     */
    private fun clearModelFields() {
        baseUrlInput.setText("")
        modelNameInput.setText("")
        apiKeyInput.setText("")
    }
    
    /**
     * Loads a profile's configuration into the input fields.
     */
    private fun loadProfileToFields(profile: SavedModelProfile) {
        baseUrlInput.setText(profile.config.baseUrl)
        modelNameInput.setText(profile.config.modelName)
        apiKeyInput.setText(if (profile.config.apiKey == "EMPTY") "" else profile.config.apiKey)
    }
    
    /**
     * Shows the profile action menu.
     *
     * @param anchor The view to anchor the popup menu to
     */
    private fun showProfileMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_profile, popup.menu)
        
        // Disable delete and copy if no profile is selected
        popup.menu.findItem(R.id.action_delete_profile)?.isEnabled = currentProfileId != null
        popup.menu.findItem(R.id.action_copy_profile)?.isEnabled = currentProfileId != null
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save_profile -> {
                    showSaveProfileDialog()
                    true
                }
                R.id.action_copy_profile -> {
                    copyCurrentProfile()
                    true
                }
                R.id.action_delete_profile -> {
                    showDeleteProfileDialog()
                    true
                }
                else -> false
            }
        }
        
        popup.show()
    }
    
    /**
     * Shows dialog to save current configuration as a profile.
     */
    private fun showSaveProfileDialog() {
        val editText = TextInputEditText(this).apply {
            hint = getString(R.string.settings_profile_name_hint)
            // Pre-fill with current profile name if editing
            currentProfileId?.let { id ->
                savedProfiles.find { it.id == id }?.let { profile ->
                    setText(profile.displayName)
                }
            }
        }
        
        val layout = TextInputLayout(this).apply {
            addView(editText)
            setPadding(48, 16, 48, 0)
        }
        
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_save_profile)
            .setView(layout)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val name = editText.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.settings_profile_name_empty, Toast.LENGTH_SHORT).show()
                } else {
                    saveCurrentAsProfile(name)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
    
    /**
     * Saves current model configuration as a profile.
     *
     * @param displayName The display name for the profile
     */
    private fun saveCurrentAsProfile(displayName: String) {
        Logger.d(TAG, "Saving current configuration as profile: $displayName")
        val baseUrl = baseUrlInput.text?.toString()?.trim() ?: ""
        val modelName = modelNameInput.text?.toString()?.trim() ?: ""
        val apiKey = apiKeyInput.text?.toString()?.trim().let { 
            if (it.isNullOrEmpty()) "EMPTY" else it 
        }
        
        val config = ModelConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName
        )
        
        val profileId = currentProfileId ?: settingsManager.generateProfileId()
        val profile = SavedModelProfile(
            id = profileId,
            displayName = displayName,
            config = config
        )
        
        settingsManager.saveProfile(profile)
        currentProfileId = profileId
        settingsManager.setCurrentProfileId(profileId)
        
        loadSavedProfiles()
        Toast.makeText(this, R.string.settings_profile_saved, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Copies the current profile with a new name.
     */
    private fun copyCurrentProfile() {
        if (currentProfileId == null) {
            Toast.makeText(this, R.string.settings_profile_name_empty, Toast.LENGTH_SHORT).show()
            return
        }
        
        val currentProfile = savedProfiles.find { it.id == currentProfileId } ?: return
        val newName = getString(R.string.settings_copy_profile_name, currentProfile.displayName)
        
        val baseUrl = baseUrlInput.text?.toString()?.trim() ?: ""
        val modelName = modelNameInput.text?.toString()?.trim() ?: ""
        val apiKey = apiKeyInput.text?.toString()?.trim().let { 
            if (it.isNullOrEmpty()) "EMPTY" else it 
        }
        
        val config = ModelConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName
        )
        
        val newProfileId = settingsManager.generateProfileId()
        val newProfile = SavedModelProfile(
            id = newProfileId,
            displayName = newName,
            config = config
        )
        
        settingsManager.saveProfile(newProfile)
        currentProfileId = newProfileId
        settingsManager.setCurrentProfileId(newProfileId)
        
        loadSavedProfiles()
        Toast.makeText(this, R.string.settings_profile_copied, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Shows confirmation dialog to delete current profile.
     */
    private fun showDeleteProfileDialog() {
        if (currentProfileId == null) return
        
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_delete_profile)
            .setMessage(R.string.settings_delete_profile_confirm)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                deleteCurrentProfile()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
    
    /**
     * Deletes the currently selected profile.
     */
    private fun deleteCurrentProfile() {
        currentProfileId?.let { id ->
            Logger.d(TAG, "Deleting current profile: $id")
            settingsManager.deleteProfile(id)
            currentProfileId = null
            clearModelFields()
            loadSavedProfiles()
            Toast.makeText(this, R.string.settings_profile_deleted, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Validates user input before saving.
     *
     *
     * @return true if all inputs are valid, false otherwise
     */
    private fun validateInput(): Boolean {
        Logger.d(TAG, "Validating input")
        var isValid = true
        clearErrors()
        
        // Validate base URL
        val baseUrl = baseUrlInput.text?.toString()?.trim() ?: ""
        if (baseUrl.isEmpty() || !isValidUrl(baseUrl)) {
            baseUrlLayout.error = getString(R.string.settings_validation_error_url)
            isValid = false
        }
        
        // Validate model name
        val modelName = modelNameInput.text?.toString()?.trim() ?: ""
        if (modelName.isEmpty()) {
            modelNameLayout.error = getString(R.string.settings_validation_error_model)
            isValid = false
        }
        
        // Validate max steps (0 means unlimited, negative not allowed)
        val maxStepsStr = maxStepsInput.text?.toString()?.trim() ?: ""
        val maxSteps = maxStepsStr.toIntOrNull()
        if (maxSteps == null || maxSteps < 0) {
            maxStepsLayout.error = getString(R.string.settings_validation_error_steps)
            isValid = false
        }
        
        // Validate screenshot delay
        val screenshotDelayStr = screenshotDelayInput.text?.toString()?.trim() ?: ""
        val screenshotDelay = screenshotDelayStr.toDoubleOrNull() ?: -1.0
        if (screenshotDelay < 0) {
            screenshotDelayLayout.error = getString(R.string.settings_validation_error_delay)
            isValid = false
        }
        
        return isValid
    }
    
    /**
     * Checks if a string is a valid URL.
     *
     * @param url The URL string to validate
     * @return true if the URL is valid, false otherwise
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = android.net.Uri.parse(url)
            uri.scheme?.startsWith("http") == true && !uri.host.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clears all error messages from input fields.
     */
    private fun clearErrors() {
        baseUrlLayout.error = null
        modelNameLayout.error = null
        apiKeyLayout.error = null
        maxStepsLayout.error = null
        screenshotDelayLayout.error = null
    }
    
    /**
     * Saves the current settings to storage.
     *
     */
    private fun saveSettings() {
        Logger.i(TAG, "Saving settings")
        val baseUrl = baseUrlInput.text?.toString()?.trim() ?: ""
        val modelName = modelNameInput.text?.toString()?.trim() ?: ""
        val apiKey = apiKeyInput.text?.toString()?.trim().let { 
            if (it.isNullOrEmpty()) "EMPTY" else it 
        }
        val maxSteps = maxStepsInput.text?.toString()?.trim()?.toIntOrNull() ?: 100
        val screenshotDelaySeconds = screenshotDelayInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 2.0
        val screenshotDelayMs = (screenshotDelaySeconds * 1000).toLong()
        val language = if (languageEnglish.isChecked) "en" else "cn"
        
        // Create and save model config
        val modelConfig = ModelConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName
        )
        settingsManager.saveModelConfig(modelConfig)
        
        // Create and save agent config
        val agentConfig = AgentConfig(
            maxSteps = maxSteps,
            language = language,
            screenshotDelayMs = screenshotDelayMs
        )
        settingsManager.saveAgentConfig(agentConfig)
        
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
    
    /**
     * Resets all settings to default values.
     */
    private fun resetToDefaults() {
        Logger.i(TAG, "Resetting settings to defaults")
        settingsManager.clearAll()
        loadCurrentSettings()
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Tests the model connection with current configuration.
     */
    private fun testModelConnection() {
        Logger.d(TAG, "Testing model connection")
        val baseUrl = baseUrlInput.text?.toString()?.trim() ?: ""
        val modelName = modelNameInput.text?.toString()?.trim() ?: ""
        val apiKey = apiKeyInput.text?.toString()?.trim().let { 
            if (it.isNullOrEmpty()) "EMPTY" else it 
        }
        
        // Validate required fields
        if (baseUrl.isEmpty() || !isValidUrl(baseUrl) || modelName.isEmpty()) {
            Toast.makeText(this, R.string.settings_test_invalid_config, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create temporary config for testing
        val testConfig = ModelConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName,
            timeoutSeconds = 30 // Shorter timeout for testing
        )
        
        val client = ModelClient(testConfig)
        
        // Update UI to show testing state
        testConnectionButton.isEnabled = false
        testConnectionButton.text = getString(R.string.settings_testing)
        
        lifecycleScope.launch {
            val result = client.testConnection()
            
            Logger.d(TAG, "Connection test result: $result")
            
            // Update UI on main thread
            testConnectionButton.isEnabled = true
            testConnectionButton.text = getString(R.string.settings_test_connection)
            
            val message = when (result) {
                is ModelClient.TestResult.Success -> 
                    getString(R.string.settings_test_success, result.latencyMs)
                is ModelClient.TestResult.AuthError -> 
                    getString(R.string.settings_test_auth_error, result.message)
                is ModelClient.TestResult.ModelNotFound -> 
                    getString(R.string.settings_test_model_not_found, result.message)
                is ModelClient.TestResult.ServerError -> 
                    getString(R.string.settings_test_server_error, result.code, result.message)
                is ModelClient.TestResult.ConnectionError -> 
                    getString(R.string.settings_test_connection_error, result.message)
                is ModelClient.TestResult.Timeout -> 
                    getString(R.string.settings_test_timeout, result.message)
            }
            
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                Logger.d(TAG, "Back button pressed")
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    // ==================== Task Templates ====================
    
    /**
     * Loads task templates from storage.
     */
    private fun loadTaskTemplates() {
        taskTemplates.clear()
        taskTemplates.addAll(settingsManager.getTaskTemplates())
        templatesAdapter?.notifyDataSetChanged()
        updateTemplatesEmptyState()
    }
    
    /**
     * Updates the empty state visibility for templates.
     */
    private fun updateTemplatesEmptyState() {
        if (taskTemplates.isEmpty()) {
            templatesRecyclerView.visibility = View.GONE
            emptyTemplatesText.visibility = View.VISIBLE
        } else {
            templatesRecyclerView.visibility = View.VISIBLE
            emptyTemplatesText.visibility = View.GONE
        }
    }
    
    /**
     * Shows dialog to add a new template.
     */
    private fun showAddTemplateDialog() {
        showTemplateDialog(null)
    }
    
    /**
     * Shows dialog to edit an existing template.
     */
    private fun showEditTemplateDialog(template: TaskTemplate) {
        showTemplateDialog(template)
    }
    
    /**
     * Shows dialog to add or edit a template.
     *
     * @param template The template to edit, or null to create a new one
     */
    private fun showTemplateDialog(template: TaskTemplate?) {
        val isEdit = template != null
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_task_template, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.templateNameInput)
        val descInput = dialogView.findViewById<TextInputEditText>(R.id.templateDescriptionInput)
        val nameLayout = dialogView.findViewById<TextInputLayout>(R.id.templateNameLayout)
        val descLayout = dialogView.findViewById<TextInputLayout>(R.id.templateDescriptionLayout)
        
        // Pre-fill if editing
        if (isEdit) {
            nameInput.setText(template!!.name)
            descInput.setText(template.description)
        }
        
        AlertDialog.Builder(this)
            .setTitle(if (isEdit) R.string.settings_edit_template else R.string.settings_add_template)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val name = nameInput.text?.toString()?.trim() ?: ""
                val description = descInput.text?.toString()?.trim() ?: ""
                
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.settings_template_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (description.isEmpty()) {
                    Toast.makeText(this, R.string.settings_template_desc_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val newTemplate = TaskTemplate(
                    id = template?.id ?: settingsManager.generateTemplateId(),
                    name = name,
                    description = description
                )
                settingsManager.saveTaskTemplate(newTemplate)
                loadTaskTemplates()
                Toast.makeText(this, R.string.settings_template_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
    
    /**
     * Shows confirmation dialog to delete a template.
     *
     * @param template The template to delete
     */
    private fun showDeleteTemplateDialog(template: TaskTemplate) {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_delete_template)
            .setMessage(R.string.settings_delete_template_confirm)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                settingsManager.deleteTaskTemplate(template.id)
                loadTaskTemplates()
                Toast.makeText(this, R.string.settings_template_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
    
    /**
     * Adapter for task templates RecyclerView.
     *
     * @property templates The list of templates to display
     * @property onEditClick Callback when edit button is clicked
     * @property onDeleteClick Callback when delete button is clicked
     */
    private inner class TaskTemplatesAdapter(
        private val templates: List<TaskTemplate>,
        private val onEditClick: (TaskTemplate) -> Unit,
        private val onDeleteClick: (TaskTemplate) -> Unit
    ) : RecyclerView.Adapter<TaskTemplatesAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.templateName)
            val descText: TextView = view.findViewById(R.id.templateDescription)
            val editBtn: ImageButton = view.findViewById(R.id.btnEdit)
            val deleteBtn: ImageButton = view.findViewById(R.id.btnDelete)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_template, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val template = templates[position]
            holder.nameText.text = template.name
            holder.descText.text = template.description
            holder.editBtn.setOnClickListener { onEditClick(template) }
            holder.deleteBtn.setOnClickListener { onDeleteClick(template) }
        }
        
        override fun getItemCount() = templates.size
    }
    
    // ==================== System Prompt ====================
    
    /**
     * Updates the status text for system prompts.
     */
    private fun updatePromptStatus() {
        promptCnStatus.text = if (settingsManager.hasCustomSystemPrompt("cn")) {
            getString(R.string.settings_system_prompt_custom)
        } else {
            getString(R.string.settings_system_prompt_default)
        }
        
        promptEnStatus.text = if (settingsManager.hasCustomSystemPrompt("en")) {
            getString(R.string.settings_system_prompt_custom)
        } else {
            getString(R.string.settings_system_prompt_default)
        }
    }
    
    /**
     * Shows dialog to edit system prompt.
     *
     * @param language The language code ("cn" or "en")
     */
    private fun showEditPromptDialog(language: String) {
        Logger.d(TAG, "Showing edit prompt dialog for language: $language")
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_system_prompt, null)
        val promptInput = dialogView.findViewById<
            com.google.android.material.textfield.TextInputEditText
        >(R.id.promptInput)
        val btnReset = dialogView.findViewById<Button>(R.id.btnResetPrompt)
        
        // Get current prompt (custom or default template with placeholder)
        val currentPrompt = settingsManager.getCustomSystemPrompt(language)
            ?: if (language == "en") {
                com.kevinluo.autoglm.config.SystemPrompts.getEnglishPromptTemplate()
            } else {
                com.kevinluo.autoglm.config.SystemPrompts.getChinesePromptTemplate()
            }
        promptInput.setText(currentPrompt)
        
        val title = if (language == "en") {
            getString(R.string.settings_system_prompt_en)
        } else {
            getString(R.string.settings_system_prompt_cn)
        }
        
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val newPrompt = promptInput.text?.toString() ?: ""
                if (newPrompt.isNotBlank()) {
                    settingsManager.saveCustomSystemPrompt(language, newPrompt)
                    // Update SystemPrompts singleton
                    if (language == "en") {
                        com.kevinluo.autoglm.config.SystemPrompts.setCustomEnglishPrompt(newPrompt)
                    } else {
                        com.kevinluo.autoglm.config.SystemPrompts.setCustomChinesePrompt(newPrompt)
                    }
                    updatePromptStatus()
                    Toast.makeText(this, R.string.settings_system_prompt_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        
        // Reset button handler
        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_system_prompt_reset)
                .setMessage(R.string.settings_system_prompt_reset_confirm)
                .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                    settingsManager.clearCustomSystemPrompt(language)
                    // Clear from SystemPrompts singleton
                    if (language == "en") {
                        com.kevinluo.autoglm.config.SystemPrompts.setCustomEnglishPrompt(null)
                    } else {
                        com.kevinluo.autoglm.config.SystemPrompts.setCustomChinesePrompt(null)
                    }
                    updatePromptStatus()
                    Toast.makeText(this, R.string.settings_system_prompt_reset_done, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
        
        dialog.show()
    }
    
    companion object {
        private const val TAG = "SettingsActivity"
    }
    
    // ==================== Debug Logs ====================
    
    /**
     * Updates the log size display text.
     */
    private fun updateLogSizeDisplay() {
        val totalSize = LogFileManager.getTotalLogSize()
        val formattedSize = LogFileManager.formatSize(totalSize)
        logSizeText.text = getString(R.string.settings_debug_logs_size, formattedSize)
    }
    
    /**
     * Exports debug logs and opens share dialog.
     */
    private fun exportDebugLogs() {
        Logger.i(TAG, "Exporting debug logs")
        
        val logFiles = LogFileManager.getLogFiles()
        if (logFiles.isEmpty()) {
            Toast.makeText(this, R.string.settings_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        
        val shareIntent = LogFileManager.exportLogs(this)
        if (shareIntent != null) {
            try {
                startActivity(Intent.createChooser(shareIntent, getString(R.string.settings_export_logs)))
            } catch (e: ActivityNotFoundException) {
                Logger.e(TAG, "No activity found to handle log export", e)
                Toast.makeText(this, R.string.settings_logs_export_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, R.string.settings_logs_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Shows confirmation dialog to clear all logs.
     */
    private fun showClearLogsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_logs)
            .setMessage(R.string.settings_clear_logs_confirm)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                LogFileManager.clearAllLogs()
                updateLogSizeDisplay()
                Toast.makeText(this, R.string.settings_logs_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
