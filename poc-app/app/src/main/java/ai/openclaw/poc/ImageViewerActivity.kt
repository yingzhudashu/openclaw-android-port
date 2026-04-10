package ai.openclaw.poc

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * 全屏图片查看器，支持双指缩放、拖拽、双击放大/缩小
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var imageView: TouchImageView
    private lateinit var btnBack: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        imageView = findViewById(R.id.touchImageView)
        btnBack = findViewById(R.id.btnBack)

        val base64 = intent.getStringExtra("image_base64")
        if (base64 != null && base64.isNotEmpty()) {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                imageView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "图片加载失败", android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }

        btnBack.setOnClickListener { finish() }
        imageView.setOnClickListener { finish() }
    }
}

/**
 * 支持双指缩放和拖拽的自定义 ImageView
 */
class TouchImageView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : androidx.appcompat.widget.AppCompatImageView(context, attrs) {

    private var scaleFactor = 1.0f
    private var minScale = 0.5f
    private var maxScale = 5.0f

    // Translate values for panning
    private var transX = 0f
    private var transY = 0f

    // Scale gesture detector
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(minScale, maxScale)
            invalidate()
            return true
        }
    })

    // Simple gesture detector for double-tap
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scaleFactor > 1.0f) {
                // Zoom out to fit
                scaleFactor = 1.0f
                transX = 0f
                transY = 0f
            } else {
                // Zoom in to 3x at tap position
                scaleFactor = 3.0f
            }
            invalidate()
            return true
        }
    })

    // Drag state
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && scaleFactor > 1.0f) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    transX += dx
                    transY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(scaleFactor, scaleFactor, width / 2f, height / 2f)
        super.onDraw(canvas)
        canvas.restore()
    }
}
