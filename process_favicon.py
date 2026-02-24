import sys
from PIL import Image, ImageDraw

input_path = sys.argv[1]
output_path = sys.argv[2]

img = Image.open(input_path).convert("RGBA")

# Crop to square first
min_dim = min(img.size)
left = (img.size[0] - min_dim) / 2
top = (img.size[1] - min_dim) / 2
right = (img.size[0] + min_dim) / 2
bottom = (img.size[1] + min_dim) / 2
img = img.crop((left, top, right, bottom))

# Create mask
mask = Image.new("L", img.size, 0)
draw = ImageDraw.Draw(mask)
draw.ellipse((0, 0, min_dim, min_dim), fill=255)

# Apply mask
result = img.copy()
result.putalpha(mask)

# Save
result.thumbnail((128, 128), Image.Resampling.LANCZOS)
result.save(output_path, "PNG")
