package com.pmtracker.project_management_backend.storage;

import com.pmtracker.project_management_backend.common.exception.InvalidFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Перекодирует загруженную картинку: декодирует в пиксели и записывает заново своим кодировщиком.
 *
 * Это второй рубеж после {@link FileTypeValidator} (см. 1.12 IMPROVEMENTS.md). Проверка сигнатуры
 * говорит только про начало файла, а дальше в нём может лежать что угодно — классический полиглот
 * вида «валидный GIF, за которым идёт HTML со скриптом» сигнатуру проходит. После перекодирования
 * от исходного файла не остаётся ни одного байта, кроме самого изображения: полиглотный хвост,
 * EXIF с геолокацией снимка и прочие метаданные на выход просто не попадают.
 */
@Component
public class ImageSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ImageSanitizer.class);

    /**
     * Защита от «зип-бомбы для картинок»: в 5MB PNG помещается изображение на сотни мегапикселей,
     * которое при декодировании развернётся в гигабайты heap и уронит приложение. Размер берём из
     * заголовка ДО чтения пикселей. 30 мегапикселей — заметно больше камеры любого телефона, то
     * есть по легальным загрузкам порог не бьёт.
     */
    private static final long MAX_SOURCE_PIXELS = 30L * 1000 * 1000;

    private static final float JPEG_QUALITY = 0.9f;

    /**
     * @param maxDimension максимальная сторона результата; картинка больше — уменьшается
     *                     пропорционально. Ограничение обязательное, а не декоративное: без него
     *                     перекодирование может выдать файл в разы больше исходного (лимит на
     *                     загрузку проверяется по входным байтам, а на диск ложатся выходные).
     * @throws InvalidFileException если файл не удалось декодировать как изображение
     */
    public SanitizedImage sanitize(MultipartFile file, int maxDimension) {
        BufferedImage image = normalize(decode(file), maxDimension);

        // Прозрачность JPEG не умеет — картинку с альфой можно писать только в PNG.
        byte[] png = encodePng(image);
        if (image.getColorModel().hasAlpha()) {
            return new SanitizedImage(png, "image/png", ".png");
        }

        // Для остального формат выбираем по размеру результата, а не по формату исходника.
        // Разница принципиальная в обе стороны: фотография в PNG разбухает в десятки раз, а
        // логотип из десятка плоских цветов в JPEG наоборот тяжелеет — и вдобавок обрастает
        // ореолами вокруг контуров. Сжать обе версии картинки такого размера стоит миллисекунды,
        // так что гадать по эвристике незачем. При равенстве выигрывает PNG: он без потерь.
        byte[] jpeg = encodeJpeg(image);
        return jpeg.length < png.length
                ? new SanitizedImage(jpeg, "image/jpeg", ".jpg")
                : new SanitizedImage(png, "image/png", ".png");
    }

    private BufferedImage decode(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             ImageInputStream imageInput = ImageIO.createImageInputStream(in)) {
            if (imageInput == null) {
                throw new InvalidFileException("File could not be read as an image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new InvalidFileException("File could not be read as an image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_SOURCE_PIXELS) {
                    throw new InvalidFileException("Image resolution is too high (max 30 megapixels)");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidFileException("File could not be read as an image");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (InvalidFileException e) {
            throw e;
        } catch (IIOException e) {
            throw new InvalidFileException("File could not be read as an image");
        } catch (IOException e) {
            throw new InvalidFileException("Uploaded file could not be read");
        } catch (RuntimeException e) {
            // Декодеры ImageIO на битом/обрезанном файле роняют не только IIOException, но и
            // всё, что подвернётся: IndexOutOfBounds из GIF-ридера, NegativeArraySize,
            // IllegalArgument. Вход здесь заведомо недоверенный, поэтому ловим широко — иначе
            // «прислали мусор» превращается в 500. Логируем, чтобы за этой сеткой не спрятался
            // наш собственный баг.
            log.warn("Image decoding failed unexpectedly, rejecting upload", e);
            throw new InvalidFileException("File could not be read as an image");
        } catch (OutOfMemoryError e) {
            // Порог по мегапикселям снимается с заголовка, а заголовок — тоже данные от клиента.
            throw new InvalidFileException("Image is too large to process");
        }
    }

    /**
     * Уменьшение делаем в несколько шагов, каждый раз не больше чем вдвое: одношаговый билинейный
     * ресайз при сильном уменьшении даёт заметный алиасинг (берёт 4 пикселя из сотни, остальные
     * игнорирует), а половинками результат выходит гладким.
     */
    private BufferedImage normalize(BufferedImage source, int maxDimension) {
        BufferedImage current = source;
        while (Math.max(current.getWidth(), current.getHeight()) > maxDimension) {
            double factor = Math.max(0.5, (double) maxDimension / Math.max(current.getWidth(), current.getHeight()));
            int width = Math.max((int) Math.round(current.getWidth() * factor), 1);
            int height = Math.max((int) Math.round(current.getHeight() * factor), 1);
            current = redraw(current, width, height);
        }
        // Даже если уменьшать нечего, перерисовываем: это приводит исходник к обычному ARGB/RGB-
        // растру (индексированные палитры GIF, CMYK-JPEG и прочая экзотика дальше могут не
        // записаться) и заодно гарантирует, что в выходном буфере нет ничего от исходного файла.
        return current == source ? redraw(source, source.getWidth(), source.getHeight()) : current;
    }

    private BufferedImage redraw(BufferedImage source, int width, int height) {
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] encodePng(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("No PNG writer available");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode image as PNG", e);
        }
        return out.toByteArray();
    }

    private byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode image as JPEG", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
