package dev.eastar.audioprocessing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.log.Log
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.AsyncTask
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ca.uol.aig.fftpack.RealDoubleFFT
import dev.eastar.soundtest.databinding.ActivityMainBinding

//FFT(Fast Fourier Transform) DFT 알고리즘 : 데이터를 시간 기준(time base)에서 주파수 기준(frequency base)으로 바꾸는데 사용.
class SoundTest : AppCompatActivity() {

    companion object {
        const val BLOCK_SIZE = 256
        const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
        const val SAMPLE_RATE_IN_HZ = 8000
        const val CHANNEL_CONFIGURATION = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**-100% ~ +100%*/
        const val BITMAP_HEIGHT = 200
    }

    private lateinit var bb: ActivityMainBinding

    // 우리의 FFT 객체는 transformer고, 이 FFT 객체를 통해 AudioRecord 객체에서 한 번에 BLOCK_SIZE 가지 샘플을 다룬다.
    // 사용하는 샘플의 수는 FFT 객체를 통해 샘플들을 실행하고 가져올 주파수의 수와 일치한다.
    // 다른 크기를 마음대로 지정해도 되지만, 메모리와 성능 측면을 반드시 고려해야 한다.
    // 적용될 수학적 계산이 프로세서의 성능과 밀접한 관계를 보이기 때문이다.
    // RealDoubleFFT 클래스 컨스트럭터는 한번에 처리할 샘플들의 수를 받는다. 그리고 출력될 주파수 범위들의 수를 나타낸다.
    private var transformer = RealDoubleFFT(BLOCK_SIZE)

    // Bitmap 이미지를 표시하기 위해 ImageView를 사용한다. 이 이미지는 현재 오디오 스트림에서 주파수들의 레벨을 나타낸다.
    var bitmap: Bitmap = Bitmap.createBitmap(BLOCK_SIZE, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
    var canvas = Canvas(bitmap)

    var paint: Paint = Paint().apply {
        color = Color.GREEN
    }

    var audioAsyncTask: RecordAudio? = null
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bb.root)
        requestAudioPermission()

        bb.start.setOnClickListener {
            isRecord = true
            audioAsyncTask = RecordAudio().apply { execute() }
        }
        bb.stop.setOnClickListener {
            isRecord = false
            audioAsyncTask?.cancel(true)
        }
        bb.eq.setImageBitmap(bitmap)
    }

    //sensor request audio
    private fun requestAudioPermission() {
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (!it) finish()
        }.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private var isRecord: Boolean = true

    inner class RecordAudio : AsyncTask<Unit, DoubleArray, Unit>() {

        override fun doInBackground(vararg params: Unit) {
            try {
                // AudioRecord를 설정하고 사용한다.
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN_HZ, CHANNEL_CONFIGURATION, AUDIO_ENCODING)
                val audioRecord = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE_IN_HZ, CHANNEL_CONFIGURATION, AUDIO_ENCODING, bufferSize)

                // short로 이뤄진 배열인 buffer는 원시 PCM 샘플을 AudioRecord 객체에서 받는다.
                // double로 이뤄진 배열인 toTransform은 같은 데이터를 담지만 double 타입인데,
                // FFT  클래스에서는 double타입이 필요해서이다.
                val buffer = ShortArray(BLOCK_SIZE) //blockSize = 256
                val toTransform = DoubleArray(BLOCK_SIZE) //blockSize = 256
                audioRecord.startRecording()
                while (isRecord) {
                    val bufferReadResult = audioRecord.read(buffer, 0, BLOCK_SIZE)
                    // Log.i("bufferReadResult", Integer.toString(bufferReadResult));
                    // AudioRecord 객체에서 데이터를 읽은 다음에는 short 타입의 변수들을 double 타입으로 바꾸는 루프를 처리한다.
                    // 직접 타입 변환(casting)으로 이 작업을 처리할 수 없다.
                    // 값들이 전체 범위가 아니라 -1.0에서 1.0 사이라서 그렇다
                    // short를 32,767(Short.MAX_VALUE) 으로 나누면 double로 타입이 바뀌는데, 이 값이 short의 최대값이기 때문이다.
                    var i = 0
                    while (i < BLOCK_SIZE && i < bufferReadResult) {
                        toTransform[i] = buffer[i].toDouble() / Short.MAX_VALUE // 부호 있는 16비트
                        i++
                    }

                    // 이제 double값들의 배열을 FFT 객체로 넘겨준다. FFT 객체는 이 배열을 재사용하여 출력 값을 담는다.
                    // 포함된 데이터는 시간 도메인이 아니라 주파수 도메인에 존재한다.
                    // 이 말은 배열의 첫 번째 요소가 시간상으로 첫 번째 샘플이 아니라는 얘기다.
                    // 배열의 첫 번째 요소는 첫 번째 주파수 집합의 레벨을 나타낸다.
                    // 256가지 값(범위)을 사용하고 있고 샘플 비율이 8,000 이므로 배열의 각 요소가 대략 15.625Hz를 담당하게 된다.
                    // 15.625라는 숫자는 샘플 비율을 반으로 나누고(캡쳐할 수 있는 최대 주파수는 샘플 비율의 반이다. <- 누가 그랬는데...),
                    // 다시 256으로 나누어 나온 것이다.
                    // 따라서 배열의 첫 번째 요소로 나타난 데이터는 영(0)과 15.625Hz 사이에 해당하는 오디오 레벨을 의미한다.
                    //Log.i(toTransform.map { "%+5.1f".format(it) })
                    transformer.ft(toTransform)
                    //Log.i(toTransform.map { "%+5.1f".format(it) })
                    // publishProgress를 호출하면 onProgressUpdate가 호출된다.
                    publishProgress(toTransform)
                }
                audioRecord.stop()
            } catch (t: Throwable) {
                t.printStackTrace()
                Log.e("AudioRecord", "Recording Failed")
            }
            return
        }

        // 이 메소드는 최대 100픽셀의 높이로 일련의 세로선으로 화면에 데이터를 그린다.
        // 각 세로선은 배열의 요소 하나씩을 나타내므로 범위는 15.625Hz다.
        // 첫 번째 행은 범위가 약 0에서 15Hz인 주파수를 나타내고, 마지막 행은 약3,985에서 4,000Hz인 주파수를 나타낸다.
        override fun onProgressUpdate(vararg toTransforms: DoubleArray) {
            val toTransform = toTransforms[0]
            canvas.drawColor(Color.BLACK)
            val bitmapCenter = BITMAP_HEIGHT / 2F
            toTransform
                .map { it * 10 } //값이 작아서 키운다
                //.map { if (abs(it) < 10) 0 else it }
                .map { it.toFloat() }
                .mapIndexed { index, it -> index to bitmapCenter - it }
                .apply {
                    //plus 쪽
                    filter { it.second <= bitmapCenter }
                        .flatMap { listOf(it.first.f, it.second) }
                        .toFloatArray()
                        .let { canvas.drawLines(it, paint) }

                    //minus 쪽
                    filter { it.second >= bitmapCenter }
                        .flatMap { listOf(it.first.f, it.second) }
                        .toFloatArray()
                        .let { canvas.drawLines(it, paint) }
                }

            //.forEachIndexed { index, endY ->
            //    canvas.drawLines() e (index.f, bitmapCenter, index.f, endY, paint)
            //}


            bb.eq.invalidate()
        }
    }
}

val Number.f: Float get() = toFloat()