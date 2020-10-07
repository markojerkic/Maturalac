package com.markojerkic.drzavnamatura

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.*
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity() {

    // Set if this is debug version
    private var IS_DEBUG = false

    // Icon which is shown while subjects are downloaded
    private val downloadingIcon by lazy { findViewById<LinearLayout>(R.id.loading_subjects_icon) }
    // Name TextView
    private val nameTextView by lazy { findViewById<TextView>(R.id.username_textview) }
    // Multi click event timing
    private var nameTextViewClickTime: Long = -1
    private var clickCounter = 0


    // Create objects of layouts and the container of the left and right layout
    private val leftRightLinearContainer by lazy { findViewById<LinearLayout>(R.id.leftRightLinearLayout) }
    private val leftLinearLayout by lazy { findViewById<LinearLayout>(R.id.left_linearLayout) }
    private val rightLinearLayout by lazy { findViewById<LinearLayout>(R.id.right_linearLayout) }

    // Database and storage reference
    private val db: FirebaseFirestore by lazy { Firebase.firestore }

    // Progress dialog
    private val progressDialog by lazy { Dialog(this) }
    private val downloadProgressBar by lazy { progressDialog.findViewById<ProgressBar>(R.id.progress_bar) }
    private val downloadProgressText by lazy { progressDialog.findViewById<TextView>(R.id.progress_text) }
    
    // Firebase analitics
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        // Initialize firebase app
        FirebaseApp.initializeApp(this)
        firebaseAnalytics = Firebase.analytics

        // Check allowed subjects and exams
        checkAllowedExams()

        // Shared preferences for storing user name
        val sharedPreferences = this.getSharedPreferences("myprefs", 0)
        var name = sharedPreferences.getString("name", null)
        if (name == null) {
            val dialog = Dialog(this)
            dialog.setContentView(R.layout.name_enter_dialog)
            // Background is a rectangle with rounded edges, so we have to set the
            // "background under the background" to be transparent, or else
            // it shows up as white edges
            dialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val enterEditText = dialog.findViewById<EditText>(R.id.enter_name_edit_text)
            val enterButton = dialog.findViewById<Button>(R.id.enter_name_button)

            enterButton.setOnClickListener {
                name = enterEditText.text.toString()
                sharedPreferences.edit().putString("name", name).apply()
                setName(name!!)
                dialog.dismiss()
            }
            dialog.show()

        } else {
            setName(name!!)
        }

        // Set multiclick to start debug
        setOnMultiTouchDebug()

    }

    private fun setOnMultiTouchDebug() {
        nameTextView.setOnClickListener {
            if (!IS_DEBUG) {
                clickCounter++
                clickCounter %= 7

                if (nameTextViewClickTime == -1L) {
                    nameTextViewClickTime = System.currentTimeMillis()
                } else if (clickCounter == 6) {
                    if (System.currentTimeMillis() - nameTextViewClickTime <= 3000L) {
                        IS_DEBUG = true
                        // Empty subject list
                        leftLinearLayout.removeAllViews()
                        rightLinearLayout.removeAllViews()
                        // Check for allowed exams again
                        downloadingIcon.visibility = View.VISIBLE
                        checkAllowedExams()
                    }
                }
            }
        }
    }

    private fun checkAllowedExams() {
        db.collection("dozvoljeni").get().addOnSuccessListener { result ->
            val allowed = TreeMap<String, ArrayList<String>>()
            for (r in result) {
                val data = r.data
                val examList = ArrayList<String>()
                for ((index, exam) in (data["exams"] as List<String>).withIndex()) {
                    val allowedBoolean = data["allowed"] as List<Boolean>
                    if (!IS_DEBUG) {
                        if (allowedBoolean[index]) {
                            examList.add(exam)
                        }
                    } else {
                        examList.add(exam)
                    }
                }
                if (examList.size > 0)
                    allowed[data["subject"] as String] = examList
            }

            // Remove the downloading icon and display subjects
            downloadingIcon.visibility = View.GONE
            inflateSubjects(allowed)
        }
    }

    // Set name in the title of app
    private fun setName(name: String) {
        nameTextView.text = name
    }


    // Subjects are displayed in a 2 x n grid
    // THe grid is created with two vertical linear layouts (left and right) which sit inside
    // a master linear layout which is horizontal
    private fun inflateSubjects(allowed: TreeMap<String, ArrayList<String>>) {

        for ((index, entry) in allowed.entries.withIndex()) {
            // Inflate the template view of the subjects
            val subjectView =  layoutInflater.inflate(R.layout.subject_title, leftRightLinearContainer,
                false) as TextView
            // Initialize the text title from the list of subjects
            subjectView.text = entry.key
            // Initialize size to 170x170
            subjectView.width = 170
            subjectView.height = 170
            // Switch between left and right layout
            if (index % 2 == 0)
                leftLinearLayout.addView(subjectView)
            else
                rightLinearLayout.addView(subjectView)

            // Set on click action for the view
            subjectView.setOnClickListener {
                // Dialog pops up when a subject is clicked which shows which exams are available
                // for that subject
                val dialog = Dialog(this)
                dialog.setContentView(R.layout.subject_list_dialog)
                // Background is a rectangle with rounded edges, so we have to set the
                // "background under the background" to be transparent, or else
                // it shows up as white edges
                dialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

                // Initialize the list view
                val examsListView = dialog.findViewById<ListView>(R.id.exams_list_view)
                // Set the adapter
                examsListView.adapter = ExamsListAdapter(entry.value, layoutInflater)

                // Create onclick listener which will open new activity with questions from
                // the chosen exam
                examsListView.setOnItemClickListener { _, _, position, _ ->
                    if (checkInternetConnection()) {
                        val chosenYear = entry.value[position]
                        getExamQuestion(chosenYear, entry.key)


                    } else {
                        Toast.makeText(this, getString(R.string.no_internet_toast), Toast.LENGTH_LONG).show()
                    }
                }
                // Show the dialog
                dialog.show()
            }
        }

    }

    // Check if there is an internet connection
    private fun checkInternetConnection(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        return networkInfo != null && networkInfo.isConnected
    }

    private fun startExamActivity(examName: String) {
        // Show info
        Toast.makeText(this, getString(R.string.opening_exam_toast), Toast.LENGTH_LONG).show()
        // Start new activity, pass the questions through the intent
        val examActivityIntent = Intent(this, ExamActivity::class.java).apply {
            putExtra("examName", examName)
        }
        startActivity(examActivityIntent)

    }

    private fun getExamQuestion(chosenYear: String, chosenSubject: String): ArrayList<Question> {
        val examQuestion = ArrayList<Question>()
        db.collection("pitanja").whereEqualTo("subject", chosenSubject)
            .whereEqualTo("year", chosenYear).get().addOnSuccessListener { result ->
            //
                for (r in result) {
                    val data = r.data

                    // Add question to the list
                    examQuestion.add(Question(data as Map<String, Any>, r.id))
                }
                // Download images if exist
                val qs = arrayListOf<Question>()
                qs.addAll(examQuestion.sortedWith(compareBy { it -> it.questionNumber }))
                // Add questions to QuestionsObject
                QuestionsObject.addQuestions("${chosenSubject}${chosenYear}", qs)

                // Create download in progress dialog
                progressDialog.setContentView(R.layout.progress_bar_download)
                progressDialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                progressDialog.show()
                val questionImages = QuestionImages(qs)
                questionImages.checkQuestions(object : QuestionImagesProcessedCallback {
                    @Override
                    override fun done() {
                        progressDialog.dismiss()
                        startExamActivity("${chosenSubject}${chosenYear}")
                    }

                    @Override
                    override fun updateDownload(percent: Double) {
                        updateProgressDialog(percent)
                    }
                })

                firebaseAnalytics.logEvent("exam_opened") {
                    param("exam_name", "${chosenSubject}${chosenYear}")
                    param("subject_opened", chosenSubject)
                }

            }.addOnFailureListener {e -> Log.e("Firestore exception", e.toString())}
        return examQuestion

    }

    private fun updateProgressDialog(percent: Double) {
        downloadProgressBar.progress = (100 * percent).toInt()
        downloadProgressText.text = "${(100 * percent).toInt()}%"
    }

    @Override
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    @Override
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.info -> {
                val infoDialog = Dialog(this)
                infoDialog.setContentView(R.layout.about_dialog)
                // Background is a rectangle with rounded edges, so we have to set the
                // "background under the background" to be transparent, or else
                // it shows up as white edges
                infoDialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                infoDialog.show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    }
}
