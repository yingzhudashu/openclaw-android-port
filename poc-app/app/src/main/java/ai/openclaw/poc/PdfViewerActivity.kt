package ai.openclaw.poc

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 简易 PDF 查看器，基于 Android PdfRenderer
 * 支持翻页、缩放
 */
class PdfViewerActivity : AppCompatActivity() {

    private lateinit var ivPage: ImageView
    private lateinit var tvPageInfo: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton

    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var pageIndex = 0
    private var pageCount = 0
    private var scaleFactor = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        ivPage = findViewById(R.id.ivPdfPage)
        tvPageInfo = findViewById(R.id.tvPageInfo)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnClose = findViewById(R.id.btnClose)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)

        val filePath = intent.getStringExtra("file_path")
        if (filePath != null) {
            openPdfFile(filePath)
        } else {
            Toast.makeText(this, "未找到 PDF 文件", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnPrev.setOnClickListener { showPage(pageIndex - 1) }
        btnNext.setOnClickListener { showPage(pageIndex + 1) }
        btnClose.setOnClickListener { finish() }
        btnZoomIn.setOnClickListener { zoomIn() }
        btnZoomOut.setOnClickListener { zoomOut() }
    }

    private fun openPdfFile(path: String) {
        try {
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this, "文件不存在: $path", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor)
            val renderer = pdfRenderer ?: return
            pageCount = renderer.pageCount
            showPage(0)
        } catch (e: Exception) {
            Toast.makeText(this, "PDF 打开失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= pageCount) return

        currentPage?.close()
        currentPage = renderer.openPage(index)
        pageIndex = index

        val page = currentPage ?: return
        val width = (page.width * scaleFactor).toInt()
        val height = (page.height * scaleFactor).toInt()

        // Recycle old bitmap to prevent memory leak
        val oldBitmap = (ivPage.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        ivPage.setImageBitmap(bitmap)
        if (oldBitmap != null && oldBitmap != bitmap && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }

        tvPageInfo.text = "${pageIndex + 1} / $pageCount"
        btnPrev.isEnabled = pageIndex > 0
        btnNext.isEnabled = pageIndex < pageCount - 1
    }

    private fun zoomIn() {
        scaleFactor = (scaleFactor * 1.3f).coerceAtMost(3.0f)
        showPage(pageIndex)
    }

    private fun zoomOut() {
        scaleFactor = (scaleFactor / 1.3f).coerceAtLeast(0.5f)
        showPage(pageIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPage?.close()
        pdfRenderer?.close()
        // Recycle bitmap to prevent memory leak
        val oldBitmap = (ivPage.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (oldBitmap != null && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }
    }
}
