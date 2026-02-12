package dev.runningqueries

import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

// ── Data ──

data class QueryInfo(
    val pid: Int,
    val userName: String,
    val startTime: String,
    val durationSec: Int,
    val queryText: String,
)

// ── Table Model ──

class QueriesTableModel : AbstractTableModel() {
    private val columns = arrayOf("PID", "Duration", "Query")
    private var queries: List<QueryInfo> = emptyList()

    fun setQueries(list: List<QueryInfo>) {
        queries = list
        fireTableDataChanged()
    }

    fun getQueryAt(row: Int): QueryInfo = queries[row]

    override fun getRowCount() = queries.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(col: Int) = columns[col]

    override fun getValueAt(row: Int, col: Int): Any {
        val q = queries[row]
        return when (col) {
            0 -> q.pid
            1 -> formatDuration(q.durationSec)
            2 -> q.queryText
            else -> ""
        }
    }

    private fun formatDuration(sec: Int): String = when {
        sec < 60   -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else       -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}

// ── Action (entry point, bound to Cmd+.) ──

class ShowRunningQueriesAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val dataSources = DbPsiFacade.getInstance(project)
            .dataSources
            .mapNotNull { it.delegate as? LocalDataSource }

        if (dataSources.isEmpty()) return

        val panel = RunningQueriesPanel(project, dataSources)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel.table)
            .setTitle("Running Queries")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(false)
            .setCancelOnOtherWindowOpen(false)
            .setCancelKeyEnabled(true)
            .createPopup()

        panel.popup = popup
        popup.showCenteredInCurrentWindow(project)
        panel.refresh()
    }
}

// ── Popup Panel ──

class RunningQueriesPanel(
    private val project: Project,
    private val dataSources: List<LocalDataSource>,
) : JPanel(BorderLayout()) {

    val tableModel = QueriesTableModel()
    val table = JBTable(tableModel)
    var popup: JBPopup? = null

    private val statusLabel = JLabel(" Loading...")
    private val dsCombo = ComboBox(dataSources.map { it.name }.toTypedArray())

    // Bottom bar switches between "normal" and "confirm" cards
    private val bottomCards = CardLayout()
    private val bottomPanel = JPanel(bottomCards)
    private val confirmLabel = JLabel()
    private var pendingKillPid: Int? = null

    /** Currently selected data source. */
    private val selectedDataSource: LocalDataSource
        get() = dataSources[dsCombo.selectedIndex.coerceIn(dataSources.indices)]

    init {
        preferredSize = Dimension(920, 420)

        // ── Top bar: connection switcher ──
        val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        topBar.add(JLabel("Connection:"))
        topBar.add(dsCombo)
        dsCombo.addActionListener { refresh() }
        add(topBar, BorderLayout.NORTH)

        // ── Table ──
        table.setShowGrid(true)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.rowHeight = 24
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        add(JBScrollPane(table), BorderLayout.CENTER)

        // ── Bottom bar: two cards ──

        // Card 1: normal (status + Kill/Refresh buttons)
        val normalCard = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
            buttons.add(JButton("Kill").apply {
                toolTipText = "Cancel selected query (pg_cancel_backend)"
                addActionListener { askKill() }
            })
            buttons.add(JButton("Refresh").apply {
                addActionListener { refresh() }
            })
            add(statusLabel, BorderLayout.WEST)
            add(buttons, BorderLayout.EAST)
        }

        // Card 2: inline confirmation
        val confirmCard = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            background = Color(80, 40, 40)
            confirmLabel.foreground = Color.WHITE
            add(confirmLabel, BorderLayout.WEST)
            val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
            }
            btns.add(JButton("Yes, Kill").apply {
                addActionListener { doKill() }
            })
            btns.add(JButton("Cancel").apply {
                addActionListener { showNormalBar() }
            })
            add(btns, BorderLayout.EAST)
        }

        bottomPanel.add(normalCard, "normal")
        bottomPanel.add(confirmCard, "confirm")
        bottomCards.show(bottomPanel, "normal")
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun showNormalBar() {
        pendingKillPid = null
        bottomCards.show(bottomPanel, "normal")
    }

    private fun showConfirmBar(pid: Int, queryText: String) {
        pendingKillPid = pid
        val short = if (queryText.length > 80) queryText.take(80) + "…" else queryText
        confirmLabel.text = " Kill PID $pid?  $short"
        bottomCards.show(bottomPanel, "confirm")
    }

    // ── Refresh ──

    fun refresh() {
        showNormalBar()
        val ds = selectedDataSource
        statusLabel.text = " Loading..."

        object : Task.Backgroundable(project, "Fetching running queries…", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val list = fetchQueries(ds)
                    SwingUtilities.invokeLater {
                        tableModel.setQueries(list)
                        statusLabel.text = " ${list.size} running on ${ds.name}"
                        adjustColumns()
                    }
                } catch (ex: Exception) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = " Error: ${ex.message}"
                    }
                }
            }
        }.queue()
    }

    // ── Kill ──

    private fun askKill() {
        val row = table.selectedRow
        if (row < 0) {
            statusLabel.text = " Select a query first"
            return
        }
        val q = tableModel.getQueryAt(row)
        showConfirmBar(q.pid, q.queryText)
    }

    private fun doKill() {
        val pid = pendingKillPid ?: return
        showNormalBar()
        statusLabel.text = " Killing PID $pid..."

        val ds = selectedDataSource
        object : Task.Backgroundable(project, "Cancelling query…", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    cancelQuery(ds, pid)
                    SwingUtilities.invokeLater {
                        statusLabel.text = " PID $pid cancelled"
                        refresh()
                    }
                } catch (ex: Exception) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = " Kill failed: ${ex.message}"
                    }
                }
            }
        }.queue()
    }

    private fun adjustColumns() {
        if (table.columnCount < 3) return
        table.columnModel.getColumn(0).preferredWidth = 70
        table.columnModel.getColumn(1).preferredWidth = 90
        table.columnModel.getColumn(2).preferredWidth = 760
    }

    // ── DB helpers ──

    /**
     * Opens a connection to [ds] via DataSpell's DatabaseConnectionManager,
     * executes [block] with the RemoteConnection, and closes afterwards.
     */
    private fun <T> withConnection(ds: LocalDataSource, block: (conn: RemoteConnection) -> T): T {
        val manager = DatabaseConnectionManager.getInstance()
        val guard = kotlinx.coroutines.runBlocking {
            manager.build(project, ds).create()
        } ?: throw IllegalStateException("Could not create a database connection")

        return try {
            val dbConn = guard.get()
            block(dbConn.remoteConnection)
        } finally {
            guard.close()
        }
    }

    private fun fetchQueries(ds: LocalDataSource): List<QueryInfo> {
        return withConnection(ds) { conn ->
            val stmt = conn.createStatement()
            try {
                val rs = stmt.executeQuery(RUNNING_QUERIES_SQL)
                val list = mutableListOf<QueryInfo>()
                while (rs.next()) {
                    list += QueryInfo(
                        pid = rs.getInt("pid"),
                        userName = (rs.getString("user_name") ?: "").trim(),
                        startTime = rs.getString("starttime") ?: "",
                        durationSec = rs.getInt("duration_sec"),
                        queryText = (rs.getString("query_text") ?: "").trim(),
                    )
                }
                rs.close()
                list
            } finally {
                stmt.close()
            }
        }
    }

    private fun cancelQuery(ds: LocalDataSource, pid: Int) {
        withConnection(ds) { conn ->
            val stmt = conn.createStatement()
            try {
                stmt.execute("select pg_cancel_backend($pid)")
            } finally {
                stmt.close()
            }
        }
    }

    companion object {
        private val RUNNING_QUERIES_SQL = """
            select
                pid,
                trim(user_name) as user_name,
                starttime,
                datediff(second, starttime, getdate()) as duration_sec,
                substring(trim(query), 1, 200) as query_text
            from stv_recents
            where status = 'Running'
              and trim(user_name) = current_user
            order by starttime desc
        """.trimIndent()
    }
}
